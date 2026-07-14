#!/usr/bin/env python3
"""Token-authenticated loopback gateway for one persistent Ghidra engine session.

The gateway is deliberately a transport adapter.  It accepts the canonical protocol envelopes
defined in ``integration/protocol`` and delegates one request at a time to an injected session.
Ghidra classes, filesystem paths, and child-process handles never cross the HTTP boundary.
"""

from __future__ import annotations

import argparse
from collections import deque
from dataclasses import dataclass
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
from typing import Any, Mapping, Protocol, Sequence
from urllib.parse import parse_qs, urlsplit
import uuid


INTEGRATION_ROOT = Path(__file__).resolve().parents[1]
for dependency_directory in (
    INTEGRATION_ROOT / "protocol",
    INTEGRATION_ROOT / "ghidra",
):
    dependency = str(dependency_directory)
    if dependency not in sys.path:
        sys.path.insert(0, dependency)

from contract import (  # noqa: E402
    ERROR_CODES,
    MAX_FRAME_BYTES,
    PROGRAM_METHODS,
    PROTOCOL_VERSION,
    ContractViolation,
    authenticate_bearer_header,
    generate_gateway_token,
    validate_event,
    validate_request,
    validate_response_for_request,
)
from ghidra_bridge import GhidraInstallation  # noqa: E402
from session import (  # noqa: E402
    EngineRemoteError,
    EngineSessionError,
    EngineTimeoutError,
    GhidraEngineSession,
    SessionLimits,
)


LOOPBACK_HOST = "127.0.0.1"
REQUESTS_PATH = "/v1/requests"
EVENTS_PATH = "/v1/events"
HEALTH_PATH = "/v1/health"
BOOTSTRAP_PREFIX = "GHIDRAEX_GATEWAY/1 "

_REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_METHOD = re.compile(r"^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$")
_ORIGIN_SCHEMES = frozenset({"http", "https"})


class GatewaySession(Protocol):
    """Small session surface used by the gateway and its dependency-injected tests."""

    @property
    def is_alive(self) -> bool: ...

    @property
    def program_context(self) -> Mapping[str, Any]: ...

    def call(
        self,
        method: str,
        params: Mapping[str, Any] | None = None,
        *,
        timeout: float | None = None,
        request_id: str | None = None,
    ) -> Any: ...

    def next_event(self, *, timeout: float | None = None) -> Mapping[str, Any]: ...

    def close(self) -> None: ...


@dataclass(frozen=True)
class GatewayLimits:
    """Hard HTTP and concurrency bounds for one local gateway."""

    max_body_bytes: int = MAX_FRAME_BYTES
    max_response_bytes: int = MAX_FRAME_BYTES
    max_header_bytes: int = 16 * 1024
    max_headers: int = 32
    max_connections: int = 8
    socket_timeout: float = 10.0
    request_timeout: float = 30.0
    retained_events: int = 256
    max_events_per_stream: int = 128
    max_event_stream_bytes: int = 512 * 1024
    event_stream_seconds: float = 25.0
    event_poll_seconds: float = 0.25

    def __post_init__(self) -> None:
        for name, value in (
            ("max_body_bytes", self.max_body_bytes),
            ("max_response_bytes", self.max_response_bytes),
        ):
            if not 1_024 <= value <= MAX_FRAME_BYTES:
                raise ValueError(f"{name} must be between 1024 and {MAX_FRAME_BYTES}")
        if not 1_024 <= self.max_header_bytes <= 64 * 1024:
            raise ValueError("max_header_bytes must be between 1024 and 65536")
        if not 1 <= self.max_headers <= 100:
            raise ValueError("max_headers must be between 1 and 100")
        if not 1 <= self.max_connections <= 64:
            raise ValueError("max_connections must be between 1 and 64")
        for name, value in (
            ("socket_timeout", self.socket_timeout),
            ("request_timeout", self.request_timeout),
            ("event_stream_seconds", self.event_stream_seconds),
            ("event_poll_seconds", self.event_poll_seconds),
        ):
            if value <= 0:
                raise ValueError(f"{name} must be positive")
        if self.request_timeout > 30.0:
            raise ValueError("request_timeout must not exceed the protocol's 30 second bound")
        if self.event_stream_seconds > 300.0:
            raise ValueError("event_stream_seconds must not exceed 300 seconds")
        if self.event_poll_seconds > self.event_stream_seconds:
            raise ValueError("event_poll_seconds must not exceed event_stream_seconds")
        if not 1 <= self.retained_events <= 4_096:
            raise ValueError("retained_events must be between 1 and 4096")
        if not 1 <= self.max_events_per_stream <= self.retained_events:
            raise ValueError("max_events_per_stream must fit within retained_events")
        if not 1_024 <= self.max_event_stream_bytes <= MAX_FRAME_BYTES:
            raise ValueError(
                f"max_event_stream_bytes must be between 1024 and {MAX_FRAME_BYTES}"
            )


class ParentLeaseExpired(RuntimeError):
    """The launching host process no longer has its captured process identity."""


class ParentIdentityProbe(Protocol):
    """Return a stable creation identity while a process is alive, otherwise ``None``."""

    def identity(self, pid: int) -> str | None: ...


class SystemParentIdentityProbe:
    """Cross-platform, read-only process identity probe.

    Linux uses the kernel process start tick and boot identity. Windows uses the process creation
    FILETIME. Other POSIX systems use ``ps`` creation time, falling back to a direct-parent lease
    only when no process identity facility is available. No signal is sent to the parent.
    """

    def identity(self, pid: int) -> str | None:
        if pid <= 1:
            return None
        if sys.platform.startswith("linux"):
            identity = self._linux_identity(pid)
            if identity is not None:
                return identity
        elif os.name == "nt":
            identity = self._windows_identity(pid)
            if identity is not None:
                return identity
        elif os.name == "posix":
            identity = self._posix_identity(pid)
            if identity is not None:
                return identity
        # The direct relationship is itself a bounded identity on platforms without a portable
        # process creation-time API. Reparenting makes this marker disappear.
        return f"direct:{pid}" if os.getppid() == pid else None

    @staticmethod
    def _linux_identity(pid: int) -> str | None:
        try:
            raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
            close = raw.rfind(")")
            if close <= 0 or int(raw[: raw.find("(")].strip()) != pid:
                return None
            fields = raw[close + 1 :].strip().split()
            # fields[0] is field 3 (state); fields[19] is field 22 (process start ticks).
            if len(fields) <= 19 or fields[0] in {"X", "Z"}:
                return None
            start_ticks = int(fields[19], 10)
            boot_id_path = Path("/proc/sys/kernel/random/boot_id")
            boot_id = (
                boot_id_path.read_text(encoding="ascii").strip()
                if boot_id_path.is_file()
                else "current-boot"
            )
            if start_ticks <= 0 or not boot_id:
                return None
            return f"linux:{boot_id}:{start_ticks}"
        except (FileNotFoundError, PermissionError, OSError, UnicodeError, ValueError):
            return None

    @staticmethod
    def _windows_identity(pid: int) -> str | None:
        try:
            import ctypes
            from ctypes import wintypes

            process_query_limited_information = 0x1000
            still_active = 259
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            kernel32.OpenProcess.restype = wintypes.HANDLE
            kernel32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            kernel32.GetExitCodeProcess.restype = wintypes.BOOL
            kernel32.GetProcessTimes.argtypes = [
                wintypes.HANDLE,
                ctypes.POINTER(wintypes.FILETIME),
                ctypes.POINTER(wintypes.FILETIME),
                ctypes.POINTER(wintypes.FILETIME),
                ctypes.POINTER(wintypes.FILETIME),
            ]
            kernel32.GetProcessTimes.restype = wintypes.BOOL
            kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel32.CloseHandle.restype = wintypes.BOOL

            handle = kernel32.OpenProcess(process_query_limited_information, False, pid)
            if not handle:
                return None
            try:
                exit_code = wintypes.DWORD()
                if not kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
                    return None
                if exit_code.value != still_active:
                    return None
                creation = wintypes.FILETIME()
                exit_time = wintypes.FILETIME()
                kernel = wintypes.FILETIME()
                user = wintypes.FILETIME()
                if not kernel32.GetProcessTimes(
                    handle,
                    ctypes.byref(creation),
                    ctypes.byref(exit_time),
                    ctypes.byref(kernel),
                    ctypes.byref(user),
                ):
                    return None
                created = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
                return f"windows:{created}" if created > 0 else None
            finally:
                kernel32.CloseHandle(handle)
        except (AttributeError, ImportError, OSError, TypeError, ValueError):
            return None

    @staticmethod
    def _posix_identity(pid: int) -> str | None:
        executable = next(
            (
                candidate
                for candidate in ("/bin/ps", "/usr/bin/ps", shutil.which("ps"))
                if candidate and Path(candidate).is_file()
            ),
            None,
        )
        if executable is None:
            return None
        try:
            completed = subprocess.run(
                [executable, "-p", str(pid), "-o", "state=", "-o", "lstart="],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="strict",
                timeout=2.0,
                check=False,
                env={"LC_ALL": "C", "LANG": "C"},
            )
        except (OSError, subprocess.SubprocessError, UnicodeError):
            return None
        fields = completed.stdout.split()
        if (
            completed.returncode != 0
            or len(fields) < 2
            or fields[0].startswith(("X", "Z"))
        ):
            return None
        created = " ".join(fields[1:])
        if not created or len(created) > 128:
            return None
        return f"posix:{created}"


@dataclass(frozen=True)
class ParentLease:
    """Captured parent process creation identity, safe against ordinary PID reuse."""

    parent_pid: int
    process_identity: str
    probe: ParentIdentityProbe

    @classmethod
    def capture(
        cls,
        parent_pid: int | None = None,
        *,
        probe: ParentIdentityProbe | None = None,
    ) -> "ParentLease":
        selected = os.getppid() if parent_pid is None else parent_pid
        if type(selected) is not int or selected <= 1:
            raise ValueError("parent_pid must identify a positive non-system process")
        if selected == os.getpid():
            raise ValueError("the gateway cannot lease itself as its parent")
        selected_probe = probe or SystemParentIdentityProbe()
        try:
            identity = selected_probe.identity(selected)
        except Exception as error:
            raise ValueError("could not inspect the launching parent process") from error
        if not isinstance(identity, str) or not identity or len(identity) > 1_024:
            raise ValueError("the launching parent process is not alive or inspectable")
        return cls(selected, identity, selected_probe)

    def is_valid(self) -> bool:
        try:
            current = self.probe.identity(self.parent_pid)
        except Exception:
            return False
        return isinstance(current, str) and current == self.process_identity


class _DuplicateJsonKey(ValueError):
    pass


class _EngineContractError(RuntimeError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateJsonKey(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number is forbidden: {value}")


def _decode_request(body: bytes) -> dict[str, Any]:
    try:
        text = body.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise ValueError("request body is not valid UTF-8") from error
    try:
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite,
        )
    except (json.JSONDecodeError, _DuplicateJsonKey, ValueError) as error:
        raise ValueError(f"request body is not valid JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("request body root must be an object")
    return value


def _encode_json(value: Mapping[str, Any]) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _validated_origins(values: Sequence[str]) -> frozenset[str]:
    result: set[str] = set()
    for raw in values:
        origin = raw.strip()
        try:
            parsed = urlsplit(origin)
            port = parsed.port
        except ValueError as error:
            raise ValueError(f"invalid allowed origin: {raw!r}") from error
        if (
            parsed.scheme not in _ORIGIN_SCHEMES
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or parsed.path not in {"", "/"}
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError(f"allowed origin must be an exact HTTP(S) origin: {raw!r}")
        if parsed.path == "/":
            origin = origin[:-1]
        if port is not None and not 1 <= port <= 65_535:
            raise ValueError(f"allowed origin port is invalid: {raw!r}")
        if "*" in origin or origin == "null":
            raise ValueError("wildcard and null origins are forbidden")
        result.add(origin)
    return frozenset(result)


def _session_program_context(session: GatewaySession) -> dict[str, Any]:
    """Accept the public session property while tolerating a canonical nested ready event."""

    try:
        context = session.program_context
    except (AttributeError, EngineSessionError):
        context = {}
    if isinstance(context, Mapping) and context:
        return dict(context)
    ready = getattr(session, "ready_event", None)
    if isinstance(ready, Mapping):
        data = ready.get("data")
        if isinstance(data, Mapping) and isinstance(data.get("context"), Mapping):
            return dict(data["context"])
    return {}


def _error_envelope(
    request: Mapping[str, Any],
    code: str,
    message: str,
    *,
    retryable: bool = False,
) -> dict[str, Any]:
    normalized = code.upper()
    if normalized not in ERROR_CODES:
        normalized = "INTERNAL"
    return {
        "kind": "response",
        "version": PROTOCOL_VERSION,
        "id": str(request["id"]),
        "method": str(request["method"]),
        "ok": False,
        "error": {
            "code": normalized,
            "message": message[:2_048] or "request failed",
            "retryable": retryable,
        },
    }


def _can_correlate(value: Mapping[str, Any]) -> bool:
    identifier = value.get("id")
    method = value.get("method")
    return (
        isinstance(identifier, str)
        and _REQUEST_ID.fullmatch(identifier) is not None
        and isinstance(method, str)
        and 3 <= len(method) <= 128
        and _METHOD.fullmatch(method) is not None
    )


def _augment_gateway_transport(method: str, result: Any) -> Any:
    if method != "session.hello" or not isinstance(result, dict):
        return result
    capabilities = result.get("capabilities")
    if not isinstance(capabilities, dict):
        return result
    transports = capabilities.get("transports")
    if not isinstance(transports, list) or "loopback-http" in transports:
        return result
    augmented = dict(result)
    augmented_capabilities = dict(capabilities)
    augmented_capabilities["transports"] = [*transports, "loopback-http"]
    augmented["capabilities"] = augmented_capabilities
    return augmented


class _BoundedThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    block_on_close = True
    allow_reuse_address = False
    request_queue_size = 16

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_class: type[BaseHTTPRequestHandler],
        gateway: "GhidraGateway",
    ) -> None:
        self.gateway = gateway
        self._connection_slots = threading.BoundedSemaphore(
            gateway.limits.max_connections
        )
        super().__init__(server_address, handler_class, bind_and_activate=True)

    def server_bind(self) -> None:
        host, _ = self.server_address
        if host != LOOPBACK_HOST:
            raise ValueError(f"gateway must bind literal {LOOPBACK_HOST}")
        super().server_bind()
        bound_host = self.socket.getsockname()[0]
        if bound_host != LOOPBACK_HOST:
            self.server_close()
            raise RuntimeError("operating system did not bind the requested loopback address")

    def process_request(self, request: socket.socket, client_address: Any) -> None:
        if not self._connection_slots.acquire(blocking=False):
            body = b'{"error":{"code":"SERVER_BUSY","message":"busy"}}'
            try:
                request.settimeout(0.25)
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"Connection: close\r\n"
                    b"Cache-Control: no-store\r\n"
                    b"X-Content-Type-Options: nosniff\r\n"
                    b"X-Frame-Options: DENY\r\n"
                    b"Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n"
                    b"Referrer-Policy: no-referrer\r\n"
                    b"Content-Type: application/json; charset=utf-8\r\n"
                    + f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
                    + body
                )
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._connection_slots.release()
            raise

    def process_request_thread(self, request: socket.socket, client_address: Any) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._connection_slots.release()


class _GatewayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "GhidraExGateway/1"
    sys_version = ""

    @property
    def gateway(self) -> "GhidraGateway":
        return self.server.gateway  # type: ignore[attr-defined,no-any-return]

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(self.gateway.limits.socket_timeout)
        self._allowed_origin: str | None = None

    def version_string(self) -> str:
        return self.server_version

    def log_message(self, _format: str, *args: object) -> None:
        # Request targets and headers are intentionally never logged.  In particular, a client
        # mistake must not turn a bearer credential in a URL into persistent diagnostics.
        return

    def handle_expect_100(self) -> bool:
        self._send_transport_error(HTTPStatus.EXPECTATION_FAILED, "expectation_failed")
        return False

    def send_error(
        self,
        code: int,
        message: str | None = None,
        explain: str | None = None,
    ) -> None:
        del message, explain
        self._send_transport_error(code, "http_error")

    def do_GET(self) -> None:  # noqa: N802
        route, query = self._target()
        if route is None or not self._authorize():
            return
        if route == HEALTH_PATH and not query:
            self._send_json(HTTPStatus.OK, self.gateway.health())
            return
        if route == EVENTS_PATH:
            after = self._parse_event_cursor(query)
            if after is None:
                return
            self._stream_events(after)
            return
        if route == REQUESTS_PATH:
            self._send_transport_error(
                HTTPStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed",
                extra_headers={"Allow": "POST, OPTIONS"},
            )
            return
        self._send_transport_error(HTTPStatus.NOT_FOUND, "not_found")

    def do_POST(self) -> None:  # noqa: N802
        route, query = self._target()
        if route is None or not self._authorize():
            return
        if route != REQUESTS_PATH or query:
            if route in {HEALTH_PATH, EVENTS_PATH}:
                self._send_transport_error(
                    HTTPStatus.METHOD_NOT_ALLOWED,
                    "method_not_allowed",
                    extra_headers={"Allow": "GET, OPTIONS"},
                )
            else:
                self._send_transport_error(HTTPStatus.NOT_FOUND, "not_found")
            return
        body = self._read_json_body()
        if body is None:
            return
        try:
            request = _decode_request(body)
        except ValueError:
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_json")
            return
        try:
            validate_request(request)
        except ContractViolation as error:
            if _can_correlate(request):
                response = _error_envelope(
                    request,
                    error.code,
                    f"{error.path}: {error.message}",
                    retryable=False,
                )
                self._send_json(HTTPStatus.OK, response)
            else:
                self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_request")
            return
        response = self.gateway.dispatch(request)
        self._send_json(HTTPStatus.OK, response)
        if request["method"] == "session.close" and response.get("ok") is True:
            threading.Thread(
                target=self.gateway.close,
                name="ghidraex-gateway-close",
                daemon=True,
            ).start()

    def do_OPTIONS(self) -> None:  # noqa: N802
        route, query = self._target()
        if route is None or query or not self._validate_headers_and_host():
            return
        origin = self.headers.get("Origin")
        if origin is None or origin not in self.gateway.allowed_origins:
            self._send_transport_error(HTTPStatus.FORBIDDEN, "origin_denied")
            return
        requested_method = self.headers.get("Access-Control-Request-Method", "")
        expected_method = "POST" if route == REQUESTS_PATH else "GET"
        if route not in {REQUESTS_PATH, EVENTS_PATH, HEALTH_PATH} or requested_method != expected_method:
            self._send_transport_error(HTTPStatus.FORBIDDEN, "preflight_denied")
            return
        requested_headers = {
            item.strip().lower()
            for item in self.headers.get("Access-Control-Request-Headers", "").split(",")
            if item.strip()
        }
        permitted_headers = {
            "authorization",
            "content-type",
            "x-ghidraex-after-sequence",
        }
        if not requested_headers <= permitted_headers:
            self._send_transport_error(HTTPStatus.FORBIDDEN, "preflight_denied")
            return
        self._allowed_origin = origin
        self.send_response(HTTPStatus.NO_CONTENT)
        self._security_headers()
        self.send_header("Access-Control-Allow-Methods", f"{expected_method}, OPTIONS")
        self.send_header(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-GhidraEx-After-Sequence",
        )
        self.send_header("Access-Control-Max-Age", "600")
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _unsupported_method(self) -> None:
        route, _query = self._target()
        if route is None or not self._authorize():
            return
        allowed = "POST, OPTIONS" if route == REQUESTS_PATH else "GET, OPTIONS"
        self._send_transport_error(
            HTTPStatus.METHOD_NOT_ALLOWED,
            "method_not_allowed",
            extra_headers={"Allow": allowed},
        )

    do_DELETE = _unsupported_method
    do_HEAD = _unsupported_method
    do_PATCH = _unsupported_method
    do_PUT = _unsupported_method
    do_CONNECT = _unsupported_method
    do_TRACE = _unsupported_method

    def _target(self) -> tuple[str | None, str]:
        try:
            parsed = urlsplit(self.path)
        except ValueError:
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_target")
            return None, ""
        if parsed.scheme or parsed.netloc or parsed.fragment:
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_target")
            return None, ""
        return parsed.path, parsed.query

    def _validate_headers_and_host(self) -> bool:
        raw_items = list(self.headers.raw_items())
        if len(raw_items) > self.gateway.limits.max_headers:
            self._send_transport_error(
                HTTPStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "headers_too_large"
            )
            return False
        total = sum(len(name.encode("utf-8")) + len(value.encode("utf-8")) + 4 for name, value in raw_items)
        if total > self.gateway.limits.max_header_bytes:
            self._send_transport_error(
                HTTPStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "headers_too_large"
            )
            return False
        hosts = self.headers.get_all("Host", [])
        if len(hosts) != 1 or hosts[0] != self.gateway.authority:
            self._send_transport_error(HTTPStatus.MISDIRECTED_REQUEST, "host_denied")
            return False
        origins = self.headers.get_all("Origin", [])
        if len(origins) > 1:
            self._send_transport_error(HTTPStatus.FORBIDDEN, "origin_denied")
            return False
        if origins:
            origin = origins[0]
            if origin not in self.gateway.allowed_origins:
                self._send_transport_error(HTTPStatus.FORBIDDEN, "origin_denied")
                return False
            self._allowed_origin = origin
        return True

    def _authorize(self) -> bool:
        if not self._validate_headers_and_host():
            return False
        authorizations = self.headers.get_all("Authorization", [])
        if len(authorizations) != 1 or not authenticate_bearer_header(
            authorizations[0], self.gateway.token
        ):
            self._send_transport_error(
                HTTPStatus.UNAUTHORIZED,
                "unauthorized",
                extra_headers={"WWW-Authenticate": 'Bearer realm="GhidraEx"'},
            )
            return False
        return True

    def _read_json_body(self) -> bytes | None:
        if self.headers.get_all("Transfer-Encoding", []):
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "transfer_encoding_denied")
            return None
        if self.headers.get_all("Content-Encoding", []):
            self._send_transport_error(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "content_encoding_denied")
            return None
        content_types = self.headers.get_all("Content-Type", [])
        if len(content_types) != 1 or self.headers.get_content_type() != "application/json":
            self._send_transport_error(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "json_required")
            return None
        charset = self.headers.get_content_charset()
        if charset is not None and charset.lower() != "utf-8":
            self._send_transport_error(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, "utf8_required")
            return None
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) != 1 or re.fullmatch(r"[0-9]+", lengths[0]) is None:
            self._send_transport_error(HTTPStatus.LENGTH_REQUIRED, "content_length_required")
            return None
        length = int(lengths[0], 10)
        if length > self.gateway.limits.max_body_bytes:
            self._send_transport_error(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large")
            return None
        if length == 0:
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "empty_body")
            return None
        try:
            body = self.rfile.read(length)
        except (OSError, TimeoutError):
            self.close_connection = True
            return None
        if len(body) != length:
            self.close_connection = True
            return None
        return body

    def _parse_event_cursor(self, query: str) -> int | None:
        if not query:
            query_cursor = None
        else:
            try:
                values = parse_qs(query, strict_parsing=True, keep_blank_values=True)
            except ValueError:
                self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_event_cursor")
                return None
            if set(values) != {"after"} or len(values["after"]) != 1:
                self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_event_cursor")
                return None
            query_cursor = values["after"][0]

        header_values = self.headers.get_all("X-GhidraEx-After-Sequence", [])
        if len(header_values) > 1 or (query_cursor is not None and header_values):
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_event_cursor")
            return None
        raw = header_values[0] if header_values else query_cursor
        if raw is None:
            return 0
        if re.fullmatch(r"0|[1-9][0-9]{0,18}", raw) is None:
            self._send_transport_error(HTTPStatus.BAD_REQUEST, "invalid_event_cursor")
            return None
        return int(raw, 10)

    def _stream_events(self, after: int) -> None:
        oldest, latest = self.gateway.event_bounds()
        self.send_response(HTTPStatus.OK)
        self._security_headers()
        self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
        self.send_header("X-GhidraEx-Oldest-Sequence", str(oldest))
        self.send_header("X-GhidraEx-Latest-Sequence", str(latest))
        if oldest > 0 and after + 1 < oldest:
            self.send_header("X-GhidraEx-Sequence-Gap", "true")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        deadline = time.monotonic() + self.gateway.limits.event_stream_seconds
        sent = 0
        sent_bytes = 0
        cursor = after
        while sent < self.gateway.limits.max_events_per_stream:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or self.gateway.closed:
                break
            events = self.gateway.events_after(cursor, timeout=remaining)
            if not events:
                break
            for event in events:
                payload = _encode_json(event) + b"\n"
                if (
                    len(payload) > self.gateway.limits.max_response_bytes
                    or sent_bytes + len(payload) > self.gateway.limits.max_event_stream_bytes
                    or sent >= self.gateway.limits.max_events_per_stream
                ):
                    return
                try:
                    self.wfile.write(payload)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError, TimeoutError):
                    return
                sent += 1
                sent_bytes += len(payload)
                cursor = int(event["sequence"])

    def _security_headers(self) -> None:
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        self.send_header("Vary", "Origin")
        if self._allowed_origin is not None:
            self.send_header("Access-Control-Allow-Origin", self._allowed_origin)

    def _send_json(
        self,
        status: int,
        value: Mapping[str, Any],
        *,
        extra_headers: Mapping[str, str] | None = None,
    ) -> None:
        try:
            body = _encode_json(value)
        except (TypeError, ValueError):
            body = b'{"error":{"code":"ENCODING_FAILURE","message":"response unavailable"}}'
            status = HTTPStatus.INTERNAL_SERVER_ERROR
        if len(body) > self.gateway.limits.max_response_bytes:
            body = b'{"error":{"code":"RESPONSE_TOO_LARGE","message":"response unavailable"}}'
            status = HTTPStatus.INTERNAL_SERVER_ERROR
        self.send_response(status)
        self._security_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for name, value in (extra_headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.close_connection = True
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError, TimeoutError):
                pass

    def _send_transport_error(
        self,
        status: int,
        code: str,
        *,
        extra_headers: Mapping[str, str] | None = None,
    ) -> None:
        self._send_json(
            status,
            {"error": {"code": code, "message": "request was not accepted"}},
            extra_headers=extra_headers,
        )


class GhidraGateway:
    """Own a bounded loopback HTTP listener and one injected engine session."""

    def __init__(
        self,
        session: GatewaySession,
        *,
        port: int = 0,
        token: str | None = None,
        allowed_origins: Sequence[str] = (),
        limits: GatewayLimits | None = None,
        parent_lease: ParentLease | None = None,
        parent_poll_seconds: float = 1.0,
    ) -> None:
        if type(port) is not int or not 0 <= port <= 65_535:
            raise ValueError("port must be between 0 and 65535")
        self.session = session
        self.token = token or generate_gateway_token(32)
        # Reuse the constant-time authenticator as the single token-shape check.
        if not authenticate_bearer_header(f"Bearer {self.token}", self.token):
            raise ValueError("token must be a URL-safe bearer credential with at least 256 bits")
        self.allowed_origins = _validated_origins(allowed_origins)
        self.limits = limits or GatewayLimits()
        if not 0.1 <= parent_poll_seconds <= 60.0:
            raise ValueError("parent_poll_seconds must be between 0.1 and 60 seconds")
        if parent_lease is not None and not parent_lease.is_valid():
            raise ParentLeaseExpired("the launching parent identity was lost before gateway bind")
        self.parent_lease = parent_lease
        self.parent_poll_seconds = parent_poll_seconds
        self.instance_id = uuid.uuid4().hex
        self._started_at = time.monotonic()
        self._events: deque[dict[str, Any]] = deque(maxlen=self.limits.retained_events)
        self._event_condition = threading.Condition()
        self._event_fault = False
        self._event_stop = threading.Event()
        self._event_thread: threading.Thread | None = None
        self._server_thread: threading.Thread | None = None
        self._parent_thread: threading.Thread | None = None
        self._parent_stop = threading.Event()
        self._parent_lease_expired = False
        self._close_lock = threading.Lock()
        self._closed = False
        self._httpd = _BoundedThreadingHTTPServer(
            (LOOPBACK_HOST, port), _GatewayHandler, self
        )
        bound_host, bound_port = self._httpd.server_address[:2]
        if bound_host != LOOPBACK_HOST or not 1 <= int(bound_port) <= 65_535:
            self._httpd.server_close()
            raise RuntimeError("gateway received an invalid loopback listener")
        self.port = int(bound_port)
        self.authority = f"{LOOPBACK_HOST}:{self.port}"
        self.base_url = f"http://{self.authority}"

    @property
    def closed(self) -> bool:
        return self._closed

    @property
    def parent_lease_expired(self) -> bool:
        return self._parent_lease_expired

    def start(self) -> "GhidraGateway":
        if self._server_thread is not None:
            raise RuntimeError("gateway has already been started")
        if self.parent_lease is not None and not self.parent_lease.is_valid():
            raise ParentLeaseExpired("the launching parent identity was lost before gateway start")
        self._seed_ready_event()
        self._event_thread = threading.Thread(
            target=self._pump_events,
            name="ghidraex-gateway-events",
            daemon=True,
        )
        self._event_thread.start()
        self._server_thread = threading.Thread(
            target=self._httpd.serve_forever,
            kwargs={"poll_interval": 0.1},
            name="ghidraex-gateway-http",
            daemon=True,
        )
        self._server_thread.start()
        if self.parent_lease is not None:
            self._parent_thread = threading.Thread(
                target=self._monitor_parent,
                name="ghidraex-gateway-parent-lease",
                daemon=True,
            )
            self._parent_thread.start()
        return self

    def dispatch(self, request: Mapping[str, Any]) -> dict[str, Any]:
        """Validate context, delegate to the worker, and restore a canonical response."""

        method = str(request["method"])
        if method in PROGRAM_METHODS:
            actual = _session_program_context(self.session)
            requested = request.get("context")
            if not isinstance(requested, Mapping) or not actual:
                return _error_envelope(
                    request,
                    "SESSION_NOT_READY",
                    "the engine program context is not ready",
                    retryable=True,
                )
            if requested.get("programId") != actual.get("programId"):
                return _error_envelope(
                    request,
                    "PROGRAM_MISMATCH",
                    "the request names another engine program",
                    retryable=True,
                )
            if requested.get("revision") != actual.get("revision"):
                return _error_envelope(
                    request,
                    "REVISION_MISMATCH",
                    "the request revision is not current",
                    retryable=True,
                )

        timeout_ms = request.get("timeoutMs")
        timeout = self.limits.request_timeout
        if type(timeout_ms) is int:
            timeout = min(timeout, timeout_ms / 1_000.0)
        try:
            result = self.session.call(
                method,
                request["params"],
                timeout=timeout,
                request_id=str(request["id"]),
            )
            result = _augment_gateway_transport(method, result)
            if not isinstance(result, dict):
                raise _EngineContractError("engine result root is not an object")
            response: dict[str, Any] = {
                "kind": "response",
                "version": PROTOCOL_VERSION,
                "id": request["id"],
                "method": method,
                "ok": True,
                "result": result,
            }
            if method in PROGRAM_METHODS:
                response["context"] = dict(request["context"])
            validate_response_for_request(request, response)
            return response
        except ContractViolation:
            return _error_envelope(
                request,
                "INTERNAL",
                "the engine returned a response that violates protocol v1",
            )
        except _EngineContractError:
            return _error_envelope(
                request,
                "INTERNAL",
                "the engine returned a response that violates protocol v1",
            )
        except EngineRemoteError as error:
            return _error_envelope(
                request,
                error.code,
                "the engine rejected the request",
                retryable=error.retryable,
            )
        except EngineTimeoutError:
            return _error_envelope(
                request,
                "DEADLINE_EXCEEDED",
                "the engine did not respond before the request deadline",
                retryable=True,
            )
        except EngineSessionError:
            return _error_envelope(
                request,
                "ENGINE_UNAVAILABLE",
                "the engine session is unavailable",
                retryable=True,
            )
        except (TypeError, ValueError):
            return _error_envelope(
                request,
                "INVALID_REQUEST",
                "the engine rejected the request envelope",
            )
        except BaseException:
            return _error_envelope(
                request,
                "INTERNAL",
                "the gateway could not complete the request",
            )

    def health(self) -> dict[str, Any]:
        try:
            alive = bool(self.session.is_alive)
        except BaseException:
            alive = False
        return {
            "kind": "health",
            "version": PROTOCOL_VERSION,
            "status": "ready" if alive and not self._closed else "unavailable",
            "protocolVersion": PROTOCOL_VERSION,
            "instanceId": self.instance_id,
            "pid": os.getpid(),
            "uptimeMs": max(0, int((time.monotonic() - self._started_at) * 1_000)),
            "eventsHealthy": not self._event_fault,
        }

    def event_bounds(self) -> tuple[int, int]:
        with self._event_condition:
            if not self._events:
                return 0, 0
            return int(self._events[0]["sequence"]), int(self._events[-1]["sequence"])

    def events_after(self, after: int, *, timeout: float) -> tuple[dict[str, Any], ...]:
        deadline = time.monotonic() + max(0.0, timeout)
        with self._event_condition:
            while not self._closed:
                matches = tuple(
                    event for event in self._events if int(event["sequence"]) > after
                )
                if matches:
                    return matches[: self.limits.max_events_per_stream]
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return ()
                self._event_condition.wait(
                    timeout=min(remaining, self.limits.event_poll_seconds)
                )
        return ()

    def close(self) -> None:
        with self._close_lock:
            if self._closed:
                return
            self._closed = True
            self._parent_stop.set()
            self._event_stop.set()
            with self._event_condition:
                self._event_condition.notify_all()
            if self._server_thread is not None and self._server_thread.is_alive():
                self._httpd.shutdown()
            self._httpd.server_close()
            try:
                self.session.close()
            finally:
                if (
                    self._server_thread is not None
                    and self._server_thread.is_alive()
                    and threading.current_thread() is not self._server_thread
                ):
                    self._server_thread.join(timeout=2.0)
                if (
                    self._event_thread is not None
                    and self._event_thread.is_alive()
                    and threading.current_thread() is not self._event_thread
                ):
                    self._event_thread.join(timeout=1.0)
                if (
                    self._parent_thread is not None
                    and self._parent_thread.is_alive()
                    and threading.current_thread() is not self._parent_thread
                ):
                    self._parent_thread.join(timeout=min(2.0, self.parent_poll_seconds + 0.5))

    def __enter__(self) -> "GhidraGateway":
        return self.start()

    def __exit__(self, *_: object) -> None:
        self.close()

    def _seed_ready_event(self) -> None:
        ready = getattr(self.session, "ready_event", None)
        if isinstance(ready, Mapping):
            self._retain_event(ready)

    def _pump_events(self) -> None:
        next_event = getattr(self.session, "next_event", None)
        if not callable(next_event):
            return
        while not self._event_stop.is_set():
            try:
                event = next_event(timeout=self.limits.event_poll_seconds)
            except (EngineTimeoutError, queue.Empty, TimeoutError):
                continue
            except EngineSessionError:
                self._event_fault = True
                with self._event_condition:
                    self._event_condition.notify_all()
                return
            except BaseException:
                self._event_fault = True
                with self._event_condition:
                    self._event_condition.notify_all()
                return
            self._retain_event(event)

    def _monitor_parent(self) -> None:
        lease = self.parent_lease
        if lease is None:
            return
        while not self._parent_stop.wait(self.parent_poll_seconds):
            if lease.is_valid():
                continue
            self._parent_lease_expired = True
            self.close()
            return

    def _retain_event(self, event: Any) -> None:
        if not isinstance(event, Mapping):
            self._event_fault = True
            return
        candidate = dict(event)
        try:
            validate_event(candidate)
        except ContractViolation:
            self._event_fault = True
            return
        with self._event_condition:
            sequence = int(candidate["sequence"])
            if self._events and sequence <= int(self._events[-1]["sequence"]):
                if sequence == int(self._events[-1]["sequence"]):
                    return
                self._event_fault = True
                return
            self._events.append(candidate)
            self._event_condition.notify_all()


def _write_private_metadata(path: Path, descriptor: Mapping[str, Any]) -> None:
    """Atomically create a new credential descriptor readable only by its owner."""

    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        file_descriptor = os.open(path, flags, 0o600)
    except FileExistsError as error:
        raise ValueError("metadata file already exists; refusing to overwrite it") from error
    try:
        if hasattr(os, "fchmod"):
            os.fchmod(file_descriptor, 0o600)
        payload = _encode_json(descriptor) + b"\n"
        with os.fdopen(file_descriptor, "wb", closefd=True) as output:
            file_descriptor = -1
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        if os.name != "nt" and stat.S_IMODE(path.stat().st_mode) != 0o600:
            path.unlink(missing_ok=True)
            raise PermissionError("metadata file permissions are not 0600")
    finally:
        if file_descriptor >= 0:
            os.close(file_descriptor)


def _bootstrap_record(gateway: GhidraGateway, credentials: str) -> dict[str, Any]:
    return {
        "kind": "gateway.ready",
        "version": PROTOCOL_VERSION,
        "protocolVersion": PROTOCOL_VERSION,
        "baseUrl": gateway.base_url,
        "pid": os.getpid(),
        "instanceId": gateway.instance_id,
        "credentials": credentials,
    }


def _start_session_with_parent_lease(
    session: GhidraEngineSession,
    lease: ParentLease,
    *,
    poll_seconds: float,
) -> GhidraEngineSession:
    """Start a potentially slow Ghidra import while the launching host remains leased."""

    if not 0.1 <= poll_seconds <= 60.0:
        raise ValueError("parent_poll_seconds must be between 0.1 and 60 seconds")
    if not lease.is_valid():
        raise ParentLeaseExpired("the launching parent identity was lost before engine startup")

    outcome: queue.Queue[object] = queue.Queue(maxsize=1)

    def start_session() -> None:
        try:
            outcome.put_nowait(session.start())
        except BaseException as error:
            outcome.put_nowait(error)

    thread = threading.Thread(
        target=start_session,
        name="ghidraex-engine-startup",
        daemon=True,
    )
    thread.start()
    while True:
        try:
            result = outcome.get(timeout=poll_seconds)
        except queue.Empty:
            if lease.is_valid():
                continue
            session.close()
            raise ParentLeaseExpired(
                "the launching parent identity was lost during engine startup"
            )
        if isinstance(result, BaseException):
            raise result
        if not lease.is_valid():
            session.close()
            raise ParentLeaseExpired(
                "the launching parent identity was lost as engine startup completed"
            )
        if result is not session:
            session.close()
            raise EngineSessionError("engine startup returned another session instance")
        return session


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Start a private loopback gateway for one analyzed binary."
    )
    parser.add_argument("binary", type=Path, help="native binary imported at session launch")
    parser.add_argument("--ghidra-home", type=Path, help="extracted official Ghidra directory")
    parser.add_argument("--ghidra-java-home", type=Path, help="JDK used only by Ghidra")
    parser.add_argument("--port", type=int, default=0, help="loopback port; default 0 is ephemeral")
    parser.add_argument(
        "--origin",
        action="append",
        default=[],
        help="exact browser Origin allowed to call the gateway; repeatable",
    )
    parser.add_argument(
        "--metadata-file",
        type=Path,
        help="new 0600 file receiving the bearer token and private descriptor",
    )
    parser.add_argument("--startup-timeout", type=float, default=600.0)
    parser.add_argument("--request-timeout", type=float, default=30.0)
    parser.add_argument(
        "--cancellation-grace",
        type=float,
        default=1.0,
        help="seconds allowed for cooperative cancellation before process-tree termination",
    )
    parser.add_argument("--shutdown-timeout", type=float, default=10.0)
    parser.add_argument("--analysis-timeout", type=int, default=300)
    parser.add_argument("--max-cpu", type=int, default=2)
    parser.add_argument("--max-connections", type=int, default=8)
    parser.add_argument(
        "--parent-pid",
        type=int,
        help="launching host PID; defaults to the gateway process parent",
    )
    parser.add_argument(
        "--parent-poll-seconds",
        type=float,
        default=1.0,
        help="bounded launcher identity check interval (0.1 through 60 seconds)",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _argument_parser().parse_args(argv)
    metadata_path = (
        Path(os.path.abspath(os.path.expanduser(str(arguments.metadata_file))))
        if arguments.metadata_file is not None
        else None
    )
    session: GhidraEngineSession | None = None
    gateway: GhidraGateway | None = None
    try:
        parent_lease = ParentLease.capture(arguments.parent_pid)
        installation = GhidraInstallation.discover(
            arguments.ghidra_home,
            java_home=arguments.ghidra_java_home,
        )
        session_limits = SessionLimits(
            startup_timeout=arguments.startup_timeout,
            request_timeout=arguments.request_timeout,
            cancellation_grace=arguments.cancellation_grace,
            shutdown_timeout=arguments.shutdown_timeout,
            analysis_timeout=arguments.analysis_timeout,
            max_cpu=arguments.max_cpu,
        )
        session = GhidraEngineSession(
            installation,
            arguments.binary,
            limits=session_limits,
        )
        session = _start_session_with_parent_lease(
            session,
            parent_lease,
            poll_seconds=arguments.parent_poll_seconds,
        )
        gateway = GhidraGateway(
            session,
            port=arguments.port,
            allowed_origins=arguments.origin,
            limits=GatewayLimits(
                request_timeout=arguments.request_timeout,
                max_connections=arguments.max_connections,
            ),
            parent_lease=parent_lease,
            parent_poll_seconds=arguments.parent_poll_seconds,
        ).start()
        if gateway.closed or not parent_lease.is_valid():
            raise ParentLeaseExpired(
                "the launching parent identity was lost before gateway publication"
            )
        credentials = "not-exported"
        if metadata_path is not None:
            descriptor = {
                "baseUrl": gateway.base_url,
                "token": gateway.token,
                "protocolVersion": PROTOCOL_VERSION,
                "pid": os.getpid(),
            }
            _write_private_metadata(metadata_path, descriptor)
            credentials = "metadata-file"

        print(
            BOOTSTRAP_PREFIX
            + json.dumps(
                _bootstrap_record(gateway, credentials),
                ensure_ascii=True,
                separators=(",", ":"),
                sort_keys=True,
            ),
            flush=True,
        )

        stop = threading.Event()

        def request_stop(_signum: int, _frame: Any) -> None:
            stop.set()

        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, request_stop)
        while not stop.wait(0.25) and not gateway.closed:
            thread = gateway._server_thread
            if thread is None or not thread.is_alive():
                break
        return 3 if gateway.parent_lease_expired else 0
    except ParentLeaseExpired as error:
        print(f"GhidraEx gateway parent lease expired: {error}", file=sys.stderr)
        return 3
    except (EngineSessionError, OSError, ValueError) as error:
        print(f"GhidraEx gateway could not start: {error}", file=sys.stderr)
        return 2
    finally:
        if gateway is not None:
            gateway.close()
        elif session is not None:
            session.close()
        if metadata_path is not None:
            try:
                metadata_path.unlink(missing_ok=True)
            except OSError:
                pass


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "BOOTSTRAP_PREFIX",
    "EVENTS_PATH",
    "GatewayLimits",
    "GhidraGateway",
    "HEALTH_PATH",
    "LOOPBACK_HOST",
    "ParentIdentityProbe",
    "ParentLease",
    "ParentLeaseExpired",
    "REQUESTS_PATH",
    "SystemParentIdentityProbe",
    "main",
]
