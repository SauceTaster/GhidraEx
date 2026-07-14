#!/usr/bin/env python3
"""Supervised persistent session for one analyzed Ghidra program.

The UI never loads Ghidra classes.  This module owns an ``analyzeHeadless`` process, a
temporary project, and a versioned framed protocol to ``GhidraExServer.java``.  One session
maps to one immutable program revision and one isolated Ghidra JVM.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import hashlib
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import tempfile
import threading
import time
from typing import Any, Mapping
import uuid

try:
    from .ghidra_bridge import (
        BRIDGE_ROOT,
        SCRIPT_DIRECTORY,
        GhidraHeadlessBridge,
        GhidraInstallation,
    )
    from ..protocol.contract import (
        ContractViolation,
        decode_frame,
        encode_frame,
        generate_session_nonce,
    )
except ImportError:  # Standalone import from integration/ghidra for local tooling.
    from ghidra_bridge import (  # type: ignore[no-redef]
        BRIDGE_ROOT,
        SCRIPT_DIRECTORY,
        GhidraHeadlessBridge,
        GhidraInstallation,
    )
    import sys

    if str(BRIDGE_ROOT.parents[1]) not in sys.path:
        sys.path.insert(0, str(BRIDGE_ROOT.parents[1]))
    from integration.protocol.contract import (  # type: ignore[no-redef]
        ContractViolation,
        decode_frame,
        encode_frame,
        generate_session_nonce,
    )


PROTOCOL_VERSION = 1
SERVER_SCRIPT_NAME = "GhidraExServer.java"
DEFAULT_MAX_FRAME_BYTES = 2_097_152
PROGRAM_METHODS = frozenset(
    {
        "program.summary",
        "listing.window",
        "symbols.search",
        "decompile.function",
        "references.list",
    }
)
_METHOD = re.compile(r"^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$")
_REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class EngineSessionError(RuntimeError):
    """Base class for persistent engine failures."""


class EngineStartupError(EngineSessionError):
    """The worker did not reach ``session.ready``."""


class EngineTransportError(EngineSessionError):
    """The worker exited or violated the framing contract."""


class EngineTimeoutError(EngineSessionError):
    """A bounded startup, request, or shutdown deadline expired."""


class EngineRemoteError(EngineSessionError):
    """A valid worker response reported an operation error."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message
        self.retryable = retryable
        self.details = dict(details or {})


@dataclass(frozen=True)
class SessionLimits:
    startup_timeout: float = 600.0
    request_timeout: float = 30.0
    cancellation_grace: float = 1.0
    shutdown_timeout: float = 10.0
    analysis_timeout: int = 300
    max_cpu: int = 2
    max_frame_bytes: int = DEFAULT_MAX_FRAME_BYTES
    retained_log_lines: int = 400

    def __post_init__(self) -> None:
        if not _is_finite_number(self.startup_timeout) or self.startup_timeout <= 0:
            raise ValueError("startup_timeout must be positive")
        if not _is_finite_number(self.shutdown_timeout) or self.shutdown_timeout < 0.1:
            raise ValueError("shutdown_timeout must be at least 0.1 seconds")
        if (
            not _is_finite_number(self.request_timeout)
            or not 0.1 <= self.request_timeout <= 30.0
        ):
            raise ValueError("request_timeout must be between 0.1 and 30 seconds")
        if (
            not _is_finite_number(self.cancellation_grace)
            or not 0.05 <= self.cancellation_grace <= 5.0
        ):
            raise ValueError("cancellation_grace must be between 0.05 and 5 seconds")
        if (
            not isinstance(self.analysis_timeout, int)
            or isinstance(self.analysis_timeout, bool)
            or not isinstance(self.max_cpu, int)
            or isinstance(self.max_cpu, bool)
            or self.analysis_timeout <= 0
            or self.max_cpu <= 0
        ):
            raise ValueError("analysis_timeout and max_cpu must be positive")
        if (
            not isinstance(self.max_frame_bytes, int)
            or isinstance(self.max_frame_bytes, bool)
            or not 65_536 <= self.max_frame_bytes <= DEFAULT_MAX_FRAME_BYTES
        ):
            raise ValueError("max_frame_bytes must be between 65536 and 2097152")
        if (
            not isinstance(self.retained_log_lines, int)
            or isinstance(self.retained_log_lines, bool)
            or not 1 <= self.retained_log_lines <= 100_000
        ):
            raise ValueError("retained_log_lines must be between 1 and 100000")


@dataclass(frozen=True)
class _PendingRequest:
    method: str
    context: Mapping[str, Any] | None
    destination: queue.Queue[object]


class GhidraEngineSession:
    """Own and supervise one persistent, read-only Ghidra worker.

    ``start`` performs import and auto-analysis before returning.  Calls may originate from
    multiple client threads, although the Java worker intentionally serializes Ghidra API use.
    ``request.cancel`` remains out-of-band so it can interrupt a decompiler request.
    """

    def __init__(
        self,
        installation: GhidraInstallation,
        binary: str | os.PathLike[str],
        *,
        limits: SessionLimits | None = None,
        script_directory: str | os.PathLike[str] = SCRIPT_DIRECTORY,
        server_script_name: str = SERVER_SCRIPT_NAME,
    ) -> None:
        self.installation = installation
        self.binary = Path(binary).expanduser().resolve()
        self.limits = limits or SessionLimits()
        self._script_directory = Path(script_directory).expanduser().resolve()
        self._server_script_name = server_script_name
        self._nonce = generate_session_nonce()
        self._project: tempfile.TemporaryDirectory[str] | None = None
        self._process: subprocess.Popen[bytes] | None = None
        self._reader: threading.Thread | None = None
        self._write_lock = threading.Lock()
        self._state_lock = threading.RLock()
        self._pending: dict[str, _PendingRequest] = {}
        self._events: queue.Queue[object] = queue.Queue()
        self._logs: deque[str] = deque(maxlen=self.limits.retained_log_lines)
        self._fault: EngineTransportError | None = None
        self._ready: dict[str, Any] | None = None
        self._hello: dict[str, Any] | None = None
        self._expected_sha256: str | None = None
        self._last_event_sequence = 0
        self._closing = False
        self._closed = False

    @property
    def ready_event(self) -> Mapping[str, Any]:
        if self._ready is None:
            raise EngineSessionError("the engine session is not ready")
        return dict(self._ready)

    @property
    def program_context(self) -> Mapping[str, Any]:
        ready = self.ready_event
        data = ready.get("data")
        context = data.get("context") if isinstance(data, dict) else None
        return dict(context) if isinstance(context, dict) else {}

    @property
    def negotiated_capabilities(self) -> Mapping[str, Any]:
        if self._hello is None:
            raise EngineSessionError("the engine protocol has not been negotiated")
        capabilities = self._hello.get("capabilities")
        return dict(capabilities) if isinstance(capabilities, dict) else {}

    @property
    def logs(self) -> tuple[str, ...]:
        with self._state_lock:
            return tuple(self._logs)

    @property
    def is_alive(self) -> bool:
        process = self._process
        return process is not None and process.poll() is None and not self._closed

    def build_command(self, project_directory: Path, project_name: str) -> list[str]:
        command = [
            str(self.installation.analyze_headless),
            str(project_directory),
            project_name,
            "-import",
            str(self.binary),
            "-overwrite",
            "-analysisTimeoutPerFile",
            str(self.limits.analysis_timeout),
            "-max-cpu",
            str(self.limits.max_cpu),
            "-scriptPath",
            str(self._script_directory),
            "-postScript",
            self._server_script_name,
            self._nonce,
            str(self.limits.max_frame_bytes),
            "-deleteProject",
        ]
        if os.name == "nt" and self.installation.analyze_headless.suffix.lower() == ".bat":
            return ["cmd.exe", "/d", "/s", "/c", *command]
        return command

    def start(self) -> "GhidraEngineSession":
        with self._state_lock:
            if self._process is not None:
                raise EngineSessionError("the engine session has already been started")
            if not self.binary.is_file():
                raise EngineStartupError(f"input binary is not a regular file: {self.binary}")
            script = self._script_directory / self._server_script_name
            if not script.is_file():
                raise EngineStartupError(f"bundled persistent worker is missing: {script}")
            self._project = tempfile.TemporaryDirectory(prefix="ghidraex-session-")
            project_root = Path(self._project.name)
            digest = _file_sha256(self.binary)
            self._expected_sha256 = digest
            project_name = f"gx_{digest[:16]}_{self._nonce[:8]}"
            command = self.build_command(project_root, project_name)
            environment = os.environ.copy()
            environment.setdefault("LC_ALL", "C")
            environment.setdefault("LANG", "C")
            if self.installation.java_home is not None:
                environment["JAVA_HOME"] = str(self.installation.java_home)
            options: dict[str, Any] = {}
            if os.name == "nt":
                options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            else:
                options["start_new_session"] = True
            try:
                self._process = subprocess.Popen(
                    command,
                    cwd=BRIDGE_ROOT,
                    env=environment,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    bufsize=0,
                    **options,
                )
            except OSError as error:
                self._cleanup_project()
                raise EngineStartupError(f"could not launch Ghidra: {error}") from error
            self._reader = threading.Thread(
                target=self._read_worker_output,
                name=f"ghidraex-worker-{self._process.pid}",
                daemon=True,
            )
            self._reader.start()

        deadline = time.monotonic() + self.limits.startup_timeout
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise queue.Empty
                item = self._events.get(timeout=remaining)
                if isinstance(item, BaseException):
                    raise item
                if not isinstance(item, dict):
                    continue
                if item.get("kind") == "event" and item.get("event") == "session.ready":
                    self._validate_ready_event(item)
                    self._ready = item
                    break
        except queue.Empty as error:
            tail = "\n".join(self.logs[-40:])
            self._abort_worker()
            self._close_pipes()
            self._cleanup_project()
            raise EngineStartupError(
                f"Ghidra did not emit session.ready within {self.limits.startup_timeout:g}s"
                + (f".\n{tail}" if tail else "")
            ) from error
        except BaseException:
            self._abort_worker()
            self._close_pipes()
            self._cleanup_project()
            raise

        try:
            hello = self.call(
                "session.hello",
                {
                    "client": {"name": "ghidraex-supervisor", "version": "0.1.0"},
                    "protocol": {"min": PROTOCOL_VERSION, "max": PROTOCOL_VERSION},
                    "requestedCapabilities": [],
                },
                timeout=min(30.0, self.limits.request_timeout),
            )
            if not isinstance(hello, dict) or hello.get("selectedVersion") != PROTOCOL_VERSION:
                raise EngineStartupError("worker did not negotiate protocol version 1")
            self._hello = hello
            return self
        except BaseException:
            self._abort_worker()
            self._close_pipes()
            self._cleanup_project()
            raise

    def call(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        timeout: float | None = None,
        request_id: str | None = None,
    ) -> Any:
        if not _METHOD.fullmatch(method):
            raise ValueError(f"invalid engine method name: {method!r}")
        if params is not None and not isinstance(params, Mapping):
            raise TypeError("params must be an object")
        selected_timeout = self.limits.request_timeout if timeout is None else timeout
        if (
            not _is_finite_number(selected_timeout)
            or not 0.1 <= selected_timeout <= 30.0
        ):
            raise ValueError("request timeout must be between 0.1 and 30 seconds")
        identifier = request_id or uuid.uuid4().hex
        if not _REQUEST_ID.fullmatch(identifier):
            raise ValueError(
                "request_id must use 1-128 ASCII letters, digits, dot, underscore, colon, or dash"
            )

        response_queue: queue.Queue[object] = queue.Queue(maxsize=1)
        context: Mapping[str, Any] | None = (
            self.program_context if method in PROGRAM_METHODS else None
        )
        with self._state_lock:
            self._require_callable()
            if identifier in self._pending:
                raise ValueError(f"request id is already in flight: {identifier}")
            self._pending[identifier] = _PendingRequest(method, context, response_queue)
        request: dict[str, Any] = {
            "kind": "request",
            "version": PROTOCOL_VERSION,
            "id": identifier,
            "method": method,
            "params": dict(params or {}),
            "timeoutMs": max(100, min(30_000, math.ceil(selected_timeout * 1000))),
        }
        if context is not None:
            request["context"] = dict(context)

        try:
            self._write_frame(request)
            try:
                response = response_queue.get(timeout=selected_timeout)
            except queue.Empty as deadline_error:
                cancellation_deadline = time.monotonic() + self.limits.cancellation_grace
                cancellation_sent = self._send_cancel_best_effort(identifier)
                try:
                    terminal = response_queue.get(
                        timeout=max(0.0, cancellation_deadline - time.monotonic())
                    )
                except queue.Empty:
                    self._invalidate_after_uncancelled_timeout(
                        method,
                        identifier,
                        selected_timeout,
                    )
                    raise EngineTimeoutError(
                        f"{method} exceeded its {selected_timeout:g}s request deadline and "
                        f"did not terminate within the {self.limits.cancellation_grace:g}s "
                        "cancellation grace; the engine session was invalidated"
                    ) from deadline_error
                if isinstance(terminal, BaseException):
                    raise terminal
                # Do not retire the target id until its cancellation frame is either ahead
                # of every subsequent stdin frame or the session has been invalidated.  A
                # delayed cancel must never target a later request that reuses the same id.
                if not cancellation_sent.wait(
                    timeout=max(0.0, cancellation_deadline - time.monotonic())
                ):
                    self._invalidate_after_uncancelled_timeout(
                        method,
                        identifier,
                        selected_timeout,
                    )
                    raise EngineTimeoutError(
                        f"{method} exceeded its {selected_timeout:g}s request deadline and "
                        f"the cancellation frame did not drain within the "
                        f"{self.limits.cancellation_grace:g}s cancellation grace; "
                        "the engine session was invalidated"
                    ) from deadline_error
                with self._state_lock:
                    fault = self._fault
                if fault is not None:
                    raise fault
                # Crossing the caller's deadline remains a timeout even when the engine
                # cooperatively reports cancellation.  Consuming that terminal response
                # before retiring the id makes it safe for the session to remain usable.
                raise EngineTimeoutError(
                    f"{method} exceeded its {selected_timeout:g}s request deadline; "
                    "the engine terminated the request during cancellation grace"
                ) from deadline_error
        finally:
            with self._state_lock:
                self._pending.pop(identifier, None)

        if isinstance(response, BaseException):
            raise response
        # A different in-flight request may have invalidated the shared worker after this
        # response was queued.  Prefer the session's first canonical fault so concurrent
        # callers do not observe a mix of stale success and transport failure.
        with self._state_lock:
            fault = self._fault
        if fault is not None:
            raise fault
        if not isinstance(response, dict):
            raise EngineTransportError("worker returned a non-object response")
        if response.get("ok") is True:
            return response.get("result")
        error = response.get("error")
        if not isinstance(error, dict):
            raise EngineTransportError("worker error response has no error object")
        details = error.get("details")
        raise EngineRemoteError(
            str(error.get("code") or "internal_error"),
            str(error.get("message") or "the engine rejected the request"),
            retryable=bool(error.get("retryable", False)),
            details=details if isinstance(details, dict) else None,
        )

    def cancel(self, request_id: str, *, timeout: float = 5.0) -> bool:
        result = self.call("request.cancel", {"targetId": request_id}, timeout=timeout)
        return bool(
            isinstance(result, dict)
            and result.get("accepted") is True
            and result.get("state") == "requested"
        )

    def next_event(self, *, timeout: float | None = None) -> Mapping[str, Any]:
        try:
            item = self._events.get(timeout=timeout)
        except queue.Empty as error:
            raise EngineTimeoutError("no engine event arrived before the deadline") from error
        if isinstance(item, BaseException):
            raise item
        if not isinstance(item, dict):
            raise EngineTransportError("worker returned a non-object event")
        return item

    def close(self) -> None:
        with self._state_lock:
            if self._closed:
                return
            self._closing = True
        process = self._process
        if process is not None and process.poll() is None and self._ready is not None:
            try:
                self.call("session.close", {}, timeout=min(5.0, self.limits.shutdown_timeout))
            except EngineSessionError:
                pass
        if process is not None:
            try:
                process.wait(timeout=self.limits.shutdown_timeout)
            except subprocess.TimeoutExpired:
                self._abort_worker()
        self._close_pipes()
        with self._state_lock:
            self._closed = True
        self._cleanup_project()

    def __enter__(self) -> "GhidraEngineSession":
        return self.start()

    def __exit__(self, *_: object) -> None:
        self.close()

    def _require_callable(self) -> None:
        if self._process is None or self._ready is None:
            raise EngineSessionError("the engine session is not ready")
        if self._closed:
            raise EngineSessionError("the engine session is closed")
        if self._fault is not None:
            raise self._fault
        if self._process.poll() is not None:
            raise EngineTransportError(f"Ghidra worker exited with status {self._process.returncode}")

    def _write_frame(self, envelope: Mapping[str, Any]) -> None:
        try:
            frame = encode_frame(
                envelope,
                self._nonce,
                max_frame_bytes=self.limits.max_frame_bytes,
            )
        except ContractViolation as error:
            raise ValueError(f"request violates the engine protocol: {error}") from error
        process = self._process
        if process is None or process.stdin is None:
            raise EngineTransportError("Ghidra worker input is unavailable")
        try:
            with self._write_lock:
                process.stdin.write(frame)
                process.stdin.flush()
        except (BrokenPipeError, OSError, ValueError) as error:
            fault = self._record_fault(
                EngineTransportError(f"could not write to Ghidra worker: {error}")
            )
            raise fault from error

    def _send_cancel_best_effort(self, target_id: str) -> threading.Event:
        with self._state_lock:
            while True:
                identifier = f"cancel-{uuid.uuid4().hex}"
                if identifier not in self._pending:
                    break
        envelope = {
            "kind": "request",
            "version": PROTOCOL_VERSION,
            "id": identifier,
            "method": "request.cancel",
            "params": {"targetId": target_id},
            "timeoutMs": max(
                100,
                min(5_000, math.ceil(self.limits.cancellation_grace * 1000)),
            ),
        }
        sent = threading.Event()

        # A worker that stopped reading stdin must not make the timeout path block while
        # acquiring the write lock or filling an OS pipe.  Escalation will terminate the
        # process tree and release this daemon sender if the grace period expires.
        def send() -> None:
            try:
                self._write_frame(envelope)
            except (EngineSessionError, ValueError):
                pass
            finally:
                sent.set()

        threading.Thread(
            target=send,
            name=f"ghidraex-cancel-{target_id[:24]}",
            daemon=True,
        ).start()
        return sent

    def _invalidate_after_uncancelled_timeout(
        self,
        method: str,
        identifier: str,
        request_timeout: float,
    ) -> None:
        fault = EngineTransportError(
            f"engine request {method} ({identifier}) exceeded its {request_timeout:g}s "
            f"deadline and ignored cancellation for {self.limits.cancellation_grace:g}s; "
            "force termination of the owned Ghidra process tree was initiated"
        )
        with self._state_lock:
            self._closing = True
        self._record_fault(fault)
        # The worker already had both its request deadline and a cooperative cancellation
        # window.  Force-killing here keeps caller latency independent of JVM shutdown hooks.
        terminated = self._abort_worker(force=True)
        if not terminated:
            with self._state_lock:
                self._logs.append(
                    "force termination did not confirm worker exit within 1 second"
                )
        self._close_pipes()
        self._cleanup_project()

    def _read_worker_output(self) -> None:
        process = self._process
        if process is None or process.stdout is None:
            return
        stream = process.stdout
        while True:
            raw = stream.readline(self.limits.max_frame_bytes + 1)
            if not raw:
                break
            if len(raw) > self.limits.max_frame_bytes:
                while raw and not raw.endswith(b"\n"):
                    raw = stream.readline(self.limits.max_frame_bytes + 1)
                self._record_fault(EngineTransportError("worker emitted an oversized line"))
                self._abort_worker()
                return
            if not raw.startswith(b"GHIDRAEX/1 "):
                with self._state_lock:
                    self._logs.append(raw.rstrip(b"\r\n").decode("utf-8", errors="replace"))
                continue
            try:
                envelope = decode_frame(
                    raw,
                    self._nonce,
                    max_frame_bytes=self.limits.max_frame_bytes,
                )
                self._accept_envelope(envelope)
            except (ContractViolation, EngineTransportError) as error:
                fault = error if isinstance(error, EngineTransportError) else EngineTransportError(
                    f"worker emitted an invalid protocol frame: {error}"
                )
                self._record_fault(fault)
                self._abort_worker()
                return

        returncode = process.poll()
        if returncode is None:
            try:
                returncode = process.wait(timeout=0.2)
            except subprocess.TimeoutExpired:
                returncode = None
        with self._state_lock:
            expected = self._closing
        if not expected:
            self._record_fault(
                EngineTransportError(
                    "Ghidra worker output closed"
                    + (f" with status {returncode}" if returncode is not None else "")
                )
            )

    def _accept_envelope(self, envelope: Any) -> None:
        if not isinstance(envelope, dict):
            raise EngineTransportError("framed payload root must be an object")
        if envelope.get("version") != PROTOCOL_VERSION:
            raise EngineTransportError(
                f"worker protocol version is {envelope.get('version')!r}, expected {PROTOCOL_VERSION}"
            )
        kind = envelope.get("kind")
        if kind == "event":
            if not isinstance(envelope.get("event"), str):
                raise EngineTransportError("worker event has no event name")
            sequence = envelope.get("sequence")
            if not isinstance(sequence, int) or isinstance(sequence, bool) or sequence <= 0:
                raise EngineTransportError("worker event has no positive integer sequence")
            if sequence <= self._last_event_sequence:
                raise EngineTransportError(
                    f"worker event sequence regressed from {self._last_event_sequence} to {sequence}"
                )
            self._last_event_sequence = sequence
            self._events.put(envelope)
            return
        if kind != "response":
            raise EngineTransportError(f"unexpected worker envelope kind: {kind!r}")
        identifier = envelope.get("id")
        if not isinstance(identifier, str) or not identifier:
            raise EngineTransportError("worker response has no request id")
        if not isinstance(envelope.get("ok"), bool):
            raise EngineTransportError("worker response has no boolean ok field")
        with self._state_lock:
            pending = self._pending.get(identifier)
        if pending is not None:
            if envelope.get("method") != pending.method:
                raise EngineTransportError(
                    f"worker response method {envelope.get('method')!r} does not match {pending.method!r}"
                )
            response_context = envelope.get("context")
            if pending.context is None:
                if response_context is not None:
                    raise EngineTransportError(
                        f"worker attached program context to control method {pending.method}"
                    )
            elif envelope.get("ok") is True:
                if not isinstance(response_context, dict) or response_context != pending.context:
                    raise EngineTransportError(
                        f"worker response context {response_context!r} does not match {pending.context!r}"
                    )
            elif response_context is not None and response_context != pending.context:
                raise EngineTransportError(
                    f"worker error context {response_context!r} does not match {pending.context!r}"
                )
            try:
                pending.destination.put_nowait(envelope)
            except queue.Full:
                raise EngineTransportError(f"worker duplicated response id {identifier!r}")

    def _validate_ready_event(self, envelope: Mapping[str, Any]) -> None:
        if "context" in envelope:
            raise EngineStartupError("session.ready must not carry envelope program context")
        data = envelope.get("data")
        if not isinstance(data, dict):
            raise EngineStartupError("session.ready has no data object")
        context = data.get("context")
        if not isinstance(context, dict):
            raise EngineStartupError("session.ready data has no program context")
        program_id = context.get("programId")
        revision = context.get("revision")
        if not isinstance(program_id, str) or not program_id or len(program_id) > 128:
            raise EngineStartupError("session.ready has no valid programId")
        if revision != 0:
            raise EngineStartupError(f"session.ready revision is {revision!r}; expected immutable revision 0")
        actual_hash = data.get("programSha256")
        if not isinstance(actual_hash, str) or actual_hash.lower() != self._expected_sha256:
            raise EngineStartupError(
                "Ghidra session binary SHA-256 mismatch: "
                f"expected {self._expected_sha256}, got {actual_hash or '<none>'}"
            )

    def _record_fault(self, fault: EngineTransportError) -> EngineTransportError:
        with self._state_lock:
            first_fault = self._fault is None
            if first_fault:
                self._fault = fault
            active_fault = self._fault
            destinations = (
                tuple(pending.destination for pending in self._pending.values())
                if first_fault
                else ()
            )
        for destination in destinations:
            try:
                destination.put_nowait(active_fault)
            except queue.Full:
                pass
        if first_fault:
            self._events.put(active_fault)
        return active_fault

    def _abort_worker(self, *, force: bool = False) -> bool:
        """Boundedly terminate the owned process tree and report direct-child exit.

        SIGKILL/taskkill is the strongest portable action available, but an OS may leave a
        process in an uninterruptible state.  Forced mode therefore waits at most one second
        and returns ``False`` instead of turning request timeout escalation into an unbounded
        wait.  The session remains faulted even in that exceptional case.
        """
        process = self._process
        if process is None or process.poll() is not None:
            return True
        if force:
            try:
                GhidraHeadlessBridge._terminate_process_tree(process, force=True)
            except OSError:
                try:
                    process.kill()
                except OSError:
                    pass
            try:
                process.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                return process.poll() is not None
            return True
        try:
            GhidraHeadlessBridge._terminate_process_tree(process)
        except OSError:
            try:
                process.terminate()
            except OSError:
                pass
        if process.poll() is None:
            try:
                GhidraHeadlessBridge._terminate_process_tree(process, force=True)
            except OSError:
                try:
                    process.kill()
                except OSError:
                    pass
        return process.poll() is not None

    def _close_pipes(self) -> None:
        process = self._process
        if process is None:
            return
        if process.stdin is not None:
            try:
                process.stdin.close()
            except OSError:
                pass
        reader = self._reader
        if reader is not None and reader is not threading.current_thread():
            reader.join(timeout=1.0)
        if process.stdout is not None:
            try:
                process.stdout.close()
            except OSError:
                pass

    def _cleanup_project(self) -> None:
        with self._state_lock:
            project = self._project
            self._project = None
        if project is not None:
            project.cleanup()


def _is_finite_number(value: object) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(float(value))
    )


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


__all__ = [
    "EngineRemoteError",
    "EngineSessionError",
    "EngineStartupError",
    "EngineTimeoutError",
    "EngineTransportError",
    "GhidraEngineSession",
    "SessionLimits",
]
