from __future__ import annotations

from collections import deque
import http.client
import json
import os
from pathlib import Path
import queue
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest


TEST_ROOT = Path(__file__).resolve().parent
GATEWAY_ROOT = TEST_ROOT.parent
INTEGRATION_ROOT = GATEWAY_ROOT.parent
for directory in (GATEWAY_ROOT, INTEGRATION_ROOT / "protocol", INTEGRATION_ROOT / "ghidra"):
    sys.path.insert(0, str(directory))

from contract import CORE_LIMITS, validate_event, validate_response_for_request  # noqa: E402
from gateway import (  # noqa: E402
    BOOTSTRAP_PREFIX,
    GatewayLimits,
    GhidraGateway,
    ParentLease,
    ParentLeaseExpired,
    _bootstrap_record,
    _start_session_with_parent_lease,
    _write_private_metadata,
)
from session import EngineRemoteError, EngineTimeoutError  # noqa: E402


PROGRAM_CONTEXT = {"programId": "program-fixture", "revision": 0}
PROGRAM_SHA256 = "a" * 64


def ready_event(sequence: int = 1) -> dict[str, object]:
    return {
        "kind": "event",
        "version": 1,
        "event": "session.ready",
        "sequence": sequence,
        "data": {
            "sessionId": "session-fixture",
            "protocol": {"min": 1, "max": 1},
            "server": {"name": "ghidraex-engine", "version": "0.1.0"},
            "ghidra": {"version": "12.1.2"},
            "context": PROGRAM_CONTEXT,
            "programSha256": PROGRAM_SHA256,
        },
    }


def progress_event(sequence: int = 2) -> dict[str, object]:
    return {
        "kind": "event",
        "version": 1,
        "event": "analysis.progress",
        "sequence": sequence,
        "context": PROGRAM_CONTEXT,
        "data": {
            "phase": "analysis",
            "completed": 7,
            "total": 10,
            "message": "recovering functions",
        },
    }


def request(
    identifier: str,
    method: str,
    params: dict[str, object],
    *,
    context: dict[str, object] | None = None,
    timeout_ms: int | None = None,
) -> dict[str, object]:
    envelope: dict[str, object] = {
        "kind": "request",
        "version": 1,
        "id": identifier,
        "method": method,
        "params": params,
    }
    if context is not None:
        envelope["context"] = context
    if timeout_ms is not None:
        envelope["timeoutMs"] = timeout_ms
    return envelope


class FakeSession:
    def __init__(self) -> None:
        self.ready_event = ready_event()
        self.program_context = dict(PROGRAM_CONTEXT)
        self.is_alive = True
        self.closed = False
        self.calls: list[tuple[str, dict[str, object], float | None, str | None]] = []
        self.events: queue.Queue[object] = queue.Queue()

    def call(
        self,
        method: str,
        params: dict[str, object] | None = None,
        *,
        timeout: float | None = None,
        request_id: str | None = None,
    ) -> object:
        copied = dict(params or {})
        self.calls.append((method, copied, timeout, request_id))
        if method == "session.hello":
            return {
                "selectedVersion": 1,
                "sessionId": "session-fixture",
                "server": {"name": "ghidraex-engine", "version": "0.1.0"},
                "ghidra": {"version": "12.1.2"},
                "capabilities": {
                    "methods": [
                        "session.ping",
                        "program.summary",
                        "request.cancel",
                    ],
                    "events": ["analysis.progress"],
                    "transports": ["stdio"],
                    "limits": dict(CORE_LIMITS),
                },
            }
        if method == "session.ping":
            result: dict[str, object] = {
                "monotonicTimeMs": 1234,
                "state": "ready",
            }
            if "nonce" in copied:
                result["nonce"] = copied["nonce"]
            return result
        if method == "session.close":
            return {"accepted": True}
        if method == "request.cancel":
            return {"accepted": False, "state": "not-cancellable"}
        if method == "program.summary":
            return {
                "name": "tiny",
                "executableFormat": "Executable and Linking Format (ELF)",
                "languageId": "x86:LE:64:default",
                "compilerSpecId": "gcc",
                "imageBase": "ram:00400000",
                "minAddress": "ram:00400000",
                "maxAddress": "ram:00401fff",
                "sha256": PROGRAM_SHA256,
                "analysis": {"complete": True, "timedOut": False},
                "memoryBlockCount": 4,
                "instructionCount": 19,
                "functionCount": 3,
                "symbolCount": 8,
            }
        if method == "test.timeout":
            raise EngineTimeoutError("fixture deadline")
        if method == "test.remote-error":
            raise EngineRemoteError(
                "GHIDRA_FAILURE",
                "secret /local/path and bearer-token-value",
                retryable=True,
            )
        return {"echo": copied}

    def next_event(self, *, timeout: float | None = None) -> dict[str, object]:
        try:
            value = self.events.get(timeout=timeout)
        except queue.Empty as error:
            raise EngineTimeoutError("no event") from error
        if isinstance(value, BaseException):
            raise value
        assert isinstance(value, dict)
        return value

    def close(self) -> None:
        self.closed = True
        self.is_alive = False


class FakeParentProbe:
    def __init__(self, identity: str | None = "parent-created-at-1") -> None:
        self._identity = identity
        self._lock = threading.Lock()
        self.calls: list[int] = []

    def identity(self, pid: int) -> str | None:
        with self._lock:
            self.calls.append(pid)
            return self._identity

    def replace(self, identity: str | None) -> None:
        with self._lock:
            self._identity = identity


class BlockingStartupSession:
    def __init__(self) -> None:
        self.started = threading.Event()
        self.closed = threading.Event()

    def start(self) -> "BlockingStartupSession":
        self.started.set()
        self.closed.wait(5.0)
        return self

    def close(self) -> None:
        self.closed.set()


class GatewayHttpTest(unittest.TestCase):
    def setUp(self) -> None:
        self.session = FakeSession()
        self.gateway = GhidraGateway(
            self.session,
            allowed_origins=("http://127.0.0.1:5173",),
            limits=GatewayLimits(
                max_body_bytes=1_024,
                max_response_bytes=16_384,
                max_header_bytes=4_096,
                max_headers=24,
                max_connections=4,
                socket_timeout=1.0,
                request_timeout=2.0,
                retained_events=8,
                max_events_per_stream=8,
                max_event_stream_bytes=16_384,
                event_stream_seconds=0.15,
                event_poll_seconds=0.02,
            ),
        ).start()

    def tearDown(self) -> None:
        self.gateway.close()
        self.assertTrue(self.session.closed)

    def http(
        self,
        method: str,
        path: str,
        *,
        value: dict[str, object] | None = None,
        raw_body: bytes | None = None,
        token: str | None | object = ...,
        host: str | None = None,
        origin: str | None = None,
        content_type: str = "application/json",
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        if value is not None and raw_body is not None:
            raise ValueError("choose value or raw_body")
        body = raw_body
        if value is not None:
            body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        headers = {
            "Host": host or self.gateway.authority,
            **(extra_headers or {}),
        }
        selected_token = self.gateway.token if token is ... else token
        if isinstance(selected_token, str):
            headers["Authorization"] = f"Bearer {selected_token}"
        if origin is not None:
            headers["Origin"] = origin
        if body is not None:
            headers["Content-Type"] = content_type
            headers["Content-Length"] = str(len(body))

        connection = http.client.HTTPConnection("127.0.0.1", self.gateway.port, timeout=2)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            return response.status, {name.lower(): item for name, item in response.getheaders()}, payload
        finally:
            connection.close()

    def rpc(self, envelope: dict[str, object]) -> tuple[int, dict[str, str], dict[str, object]]:
        status, headers, body = self.http("POST", "/v1/requests", value=envelope)
        return status, headers, json.loads(body)

    def test_ephemeral_literal_loopback_and_private_token(self) -> None:
        self.assertEqual("127.0.0.1", self.gateway._httpd.server_address[0])
        self.assertGreater(self.gateway.port, 0)
        self.assertEqual(f"127.0.0.1:{self.gateway.port}", self.gateway.authority)
        self.assertGreaterEqual(len(self.gateway.token), 43)
        self.assertNotIn(self.gateway.token, self.gateway.base_url)

    def test_health_requires_bearer_and_discloses_no_program_or_token(self) -> None:
        status, headers, body = self.http("GET", "/v1/health", token=None)
        self.assertEqual(401, status)
        self.assertEqual("no-store, max-age=0", headers["cache-control"])
        self.assertEqual("nosniff", headers["x-content-type-options"])
        self.assertNotIn(self.gateway.token.encode(), body)

        status, _headers, _body = self.http("GET", "/v1/health", token="x" * 43)
        self.assertEqual(401, status)

        status, headers, body = self.http("GET", "/v1/health")
        self.assertEqual(200, status)
        health = json.loads(body)
        self.assertEqual("ready", health["status"])
        self.assertEqual(1, health["protocolVersion"])
        self.assertNotIn("programId", body.decode())
        self.assertNotIn(self.gateway.token, body.decode())
        self.assertEqual("DENY", headers["x-frame-options"])

    def test_strict_host_and_exact_origin(self) -> None:
        status, _headers, _body = self.http(
            "GET", "/v1/health", host=f"localhost:{self.gateway.port}"
        )
        self.assertEqual(421, status)

        status, _headers, _body = self.http(
            "GET", "/v1/health", origin="https://attacker.example"
        )
        self.assertEqual(403, status)

        allowed = "http://127.0.0.1:5173"
        status, headers, _body = self.http("GET", "/v1/health", origin=allowed)
        self.assertEqual(200, status)
        self.assertEqual(allowed, headers["access-control-allow-origin"])
        self.assertNotEqual("*", headers["access-control-allow-origin"])

    def test_ping_round_trip_preserves_correlation_and_deadline(self) -> None:
        envelope = request(
            "ping-17",
            "session.ping",
            {"nonce": "alive-17"},
            timeout_ms=1_250,
        )
        status, headers, response = self.rpc(envelope)

        self.assertEqual(200, status)
        self.assertEqual("application/json; charset=utf-8", headers["content-type"])
        validate_response_for_request(envelope, response)
        self.assertEqual("ping-17", response["id"])
        self.assertEqual("session.ping", response["method"])
        self.assertEqual("alive-17", response["result"]["nonce"])
        self.assertEqual(("session.ping", {"nonce": "alive-17"}, 1.25, "ping-17"), self.session.calls[-1])

    def test_hello_advertises_the_outer_loopback_transport(self) -> None:
        envelope = request(
            "hello-1",
            "session.hello",
            {
                "client": {"name": "gateway-test", "version": "0.1.0"},
                "protocol": {"min": 1, "max": 1},
                "requestedCapabilities": ["program.summary"],
            },
        )
        status, _headers, response = self.rpc(envelope)

        self.assertEqual(200, status)
        validate_response_for_request(envelope, response)
        transports = response["result"]["capabilities"]["transports"]
        self.assertEqual(["stdio", "loopback-http"], transports)
        self.assertNotIn("context", envelope)
        self.assertNotIn("context", response)

    def test_program_context_is_checked_before_worker_dispatch(self) -> None:
        valid = request(
            "summary-ok",
            "program.summary",
            {},
            context=dict(PROGRAM_CONTEXT),
        )
        status, _headers, response = self.rpc(valid)
        self.assertEqual(200, status)
        validate_response_for_request(valid, response)
        self.assertEqual(PROGRAM_CONTEXT, response["context"])
        calls_after_valid = len(self.session.calls)

        wrong_program = request(
            "summary-wrong-program",
            "program.summary",
            {},
            context={"programId": "another-program", "revision": 0},
        )
        _status, _headers, response = self.rpc(wrong_program)
        self.assertFalse(response["ok"])
        self.assertEqual("PROGRAM_MISMATCH", response["error"]["code"])

        wrong_revision = request(
            "summary-wrong-revision",
            "program.summary",
            {},
            context={"programId": "program-fixture", "revision": 9},
        )
        _status, _headers, response = self.rpc(wrong_revision)
        self.assertFalse(response["ok"])
        self.assertEqual("REVISION_MISMATCH", response["error"]["code"])
        self.assertEqual(calls_after_valid, len(self.session.calls))

    def test_cancel_absent_state_remains_not_cancellable(self) -> None:
        envelope = request(
            "cancel-1",
            "request.cancel",
            {"targetId": "already-gone"},
        )
        _status, _headers, response = self.rpc(envelope)

        validate_response_for_request(envelope, response)
        self.assertEqual(
            {"accepted": False, "state": "not-cancellable"}, response["result"]
        )

    def test_session_close_replies_before_closing_listener_and_owned_session(self) -> None:
        envelope = request(
            "close-1",
            "session.close",
            {"reason": "host-request"},
        )
        status, _headers, response = self.rpc(envelope)

        self.assertEqual(200, status)
        validate_response_for_request(envelope, response)
        self.assertEqual({"accepted": True}, response["result"])
        deadline = time.monotonic() + 2.0
        while (not self.gateway.closed or not self.session.closed) and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.gateway.closed)
        self.assertTrue(self.session.closed)
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", self.gateway.port), timeout=0.2)

    def test_engine_failures_are_structured_and_do_not_relay_details(self) -> None:
        timeout_request = request("timeout-1", "test.timeout", {})
        _status, _headers, response = self.rpc(timeout_request)
        self.assertEqual("DEADLINE_EXCEEDED", response["error"]["code"])
        self.assertTrue(response["error"]["retryable"])

        remote_request = request("remote-1", "test.remote-error", {})
        _status, _headers, response = self.rpc(remote_request)
        rendered = json.dumps(response)
        self.assertEqual("GHIDRA_FAILURE", response["error"]["code"])
        self.assertNotIn("/local/path", rendered)
        self.assertNotIn("bearer-token-value", rendered)

    def test_invalid_json_media_type_and_body_limit_fail_at_http_boundary(self) -> None:
        status, _headers, _body = self.http(
            "POST",
            "/v1/requests",
            raw_body=b"{}",
            content_type="text/plain",
        )
        self.assertEqual(415, status)

        duplicate = (
            b'{"kind":"request","version":1,"id":"x","id":"y",'
            b'"method":"session.ping","params":{}}'
        )
        status, _headers, _body = self.http(
            "POST", "/v1/requests", raw_body=duplicate
        )
        self.assertEqual(400, status)

        status, _headers, _body = self.http(
            "POST", "/v1/requests", raw_body=b"x" * 1_025
        )
        self.assertEqual(413, status)

    def test_invalid_envelope_with_correlation_gets_protocol_error(self) -> None:
        invalid = request("bad-ping", "session.ping", {"unexpected": True})
        status, _headers, response = self.rpc(invalid)

        self.assertEqual(200, status)
        self.assertEqual("bad-ping", response["id"])
        self.assertEqual("session.ping", response["method"])
        self.assertFalse(response["ok"])
        self.assertEqual("INVALID_PARAMS", response["error"]["code"])

    def test_ready_event_is_replayed_to_late_clients_and_cursor_is_bounded(self) -> None:
        status, headers, body = self.http("GET", "/v1/events?after=0")
        self.assertEqual(200, status)
        events = [json.loads(line) for line in body.splitlines()]
        self.assertEqual(["session.ready"], [event["event"] for event in events])
        validate_event(events[0])
        self.assertEqual(PROGRAM_CONTEXT, events[0]["data"]["context"])
        self.assertEqual("1", headers["x-ghidraex-oldest-sequence"])

        self.session.events.put(progress_event())
        deadline = time.monotonic() + 1.0
        while self.gateway.event_bounds()[1] < 2 and time.monotonic() < deadline:
            time.sleep(0.01)
        status, headers, body = self.http("GET", "/v1/events?after=1")
        self.assertEqual(200, status)
        events = [json.loads(line) for line in body.splitlines()]
        self.assertEqual([2], [event["sequence"] for event in events])
        self.assertEqual("2", headers["x-ghidraex-latest-sequence"])

        status, _headers, _body = self.http("GET", "/v1/events?token=forbidden")
        self.assertEqual(400, status)

    def test_event_ring_marks_a_sequence_gap_after_bounded_eviction(self) -> None:
        for sequence in range(2, 12):
            self.session.events.put(progress_event(sequence))
        deadline = time.monotonic() + 1.0
        while self.gateway.event_bounds()[1] < 11 and time.monotonic() < deadline:
            time.sleep(0.01)

        status, headers, body = self.http("GET", "/v1/events?after=1")
        self.assertEqual(200, status)
        events = [json.loads(line) for line in body.splitlines()]
        self.assertEqual(4, events[0]["sequence"])
        self.assertEqual(11, events[-1]["sequence"])
        self.assertEqual("4", headers["x-ghidraex-oldest-sequence"])
        self.assertEqual("true", headers["x-ghidraex-sequence-gap"])

    def test_preflight_requires_an_exact_allowed_origin(self) -> None:
        headers = {
            "Origin": "http://127.0.0.1:5173",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization, content-type",
        }
        status, response_headers, _body = self.http(
            "OPTIONS",
            "/v1/requests",
            token=None,
            origin=None,
            extra_headers=headers,
        )
        self.assertEqual(204, status)
        self.assertEqual(
            "http://127.0.0.1:5173",
            response_headers["access-control-allow-origin"],
        )

    def test_unsupported_method_is_bounded_405_not_request_dispatch(self) -> None:
        status, headers, _body = self.http("PUT", "/v1/requests", raw_body=b"{}")
        self.assertEqual(405, status)
        self.assertEqual("POST, OPTIONS", headers["allow"])


class GatewayConnectionBoundTest(unittest.TestCase):
    def test_second_connection_is_rejected_while_only_slot_is_occupied(self) -> None:
        session = FakeSession()
        gateway = GhidraGateway(
            session,
            limits=GatewayLimits(
                max_connections=1,
                socket_timeout=0.5,
                event_stream_seconds=0.1,
                event_poll_seconds=0.02,
            ),
        ).start()
        first = socket.create_connection(("127.0.0.1", gateway.port), timeout=1)
        second = None
        try:
            first.sendall(b"GET /v1/health HTTP/1.1\r\n")
            time.sleep(0.05)
            second = socket.create_connection(("127.0.0.1", gateway.port), timeout=1)
            second.sendall(
                (
                    "GET /v1/health HTTP/1.1\r\n"
                    f"Host: {gateway.authority}\r\n"
                    f"Authorization: Bearer {gateway.token}\r\n\r\n"
                ).encode("ascii")
            )
            response = second.recv(1_024)
            self.assertIn(b"503 Service Unavailable", response)
            self.assertIn(b"Content-Length: 49", response)
        finally:
            first.close()
            if second is not None:
                second.close()
            gateway.close()
        self.assertTrue(session.closed)


class ParentLeaseTest(unittest.TestCase):
    def test_capture_rejects_system_self_and_uninspectable_processes(self) -> None:
        probe = FakeParentProbe()
        for invalid in (0, 1, os.getpid()):
            with self.subTest(pid=invalid):
                with self.assertRaises(ValueError):
                    ParentLease.capture(invalid, probe=probe)

        missing = FakeParentProbe(None)
        with self.assertRaisesRegex(ValueError, "not alive or inspectable"):
            ParentLease.capture(42_424, probe=missing)

    def test_process_creation_identity_prevents_pid_reuse(self) -> None:
        probe = FakeParentProbe("creation-a")
        lease = ParentLease.capture(42_424, probe=probe)
        self.assertTrue(lease.is_valid())

        probe.replace("creation-b")
        self.assertFalse(lease.is_valid(), "a reused PID retained the old parent lease")
        probe.replace(None)
        self.assertFalse(lease.is_valid())

    def test_running_gateway_closes_listener_and_session_when_parent_is_lost(self) -> None:
        probe = FakeParentProbe("creation-a")
        lease = ParentLease.capture(42_424, probe=probe)
        session = FakeSession()
        gateway = GhidraGateway(
            session,
            parent_lease=lease,
            parent_poll_seconds=0.1,
            limits=GatewayLimits(
                event_stream_seconds=0.1,
                event_poll_seconds=0.02,
            ),
        ).start()
        try:
            probe.replace(None)
            deadline = time.monotonic() + 2.0
            while (not gateway.closed or not session.closed) and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(gateway.closed)
            self.assertTrue(gateway.parent_lease_expired)
            self.assertTrue(session.closed)
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1", gateway.port), timeout=0.2)
        finally:
            gateway.close()

    def test_parent_loss_during_slow_engine_start_closes_starting_session(self) -> None:
        probe = FakeParentProbe("creation-a")
        lease = ParentLease.capture(42_424, probe=probe)
        session = BlockingStartupSession()

        def lose_parent() -> None:
            self.assertTrue(session.started.wait(1.0))
            probe.replace(None)

        mutator = threading.Thread(target=lose_parent, daemon=True)
        mutator.start()
        with self.assertRaisesRegex(ParentLeaseExpired, "during engine startup"):
            _start_session_with_parent_lease(
                session,  # type: ignore[arg-type]
                lease,
                poll_seconds=0.1,
            )
        self.assertTrue(session.closed.wait(0.5))
        mutator.join(timeout=0.5)

    def test_real_system_probe_recognizes_current_test_parent(self) -> None:
        parent = os.getppid()
        if parent <= 1:
            self.skipTest("test runner has no leaseable parent process")
        lease = ParentLease.capture(parent)
        self.assertTrue(lease.is_valid())


class PrivateBootstrapTest(unittest.TestCase):
    def test_bootstrap_omits_token_and_metadata_is_new_mode_0600(self) -> None:
        session = FakeSession()
        gateway = GhidraGateway(session)
        try:
            record = _bootstrap_record(gateway, "metadata-file")
            rendered = BOOTSTRAP_PREFIX + json.dumps(record, separators=(",", ":"))
            self.assertNotIn(gateway.token, rendered)
            self.assertNotIn("token", record)

            with tempfile.TemporaryDirectory(prefix="gateway-metadata-test-") as temporary:
                path = Path(temporary) / "private.json"
                descriptor = {
                    "baseUrl": gateway.base_url,
                    "token": gateway.token,
                    "protocolVersion": 1,
                    "pid": os.getpid(),
                }
                _write_private_metadata(path, descriptor)
                self.assertEqual(descriptor, json.loads(path.read_text(encoding="utf-8")))
                if os.name != "nt":
                    self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
                with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
                    _write_private_metadata(path, descriptor)
        finally:
            gateway.close()


if __name__ == "__main__":
    unittest.main()
