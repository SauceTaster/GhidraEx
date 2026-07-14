from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shlex
import stat
import sys
import tempfile
import threading
import time
import unittest


TEST_ROOT = Path(__file__).resolve().parent
INTEGRATION_ROOT = TEST_ROOT.parent
sys.path.insert(0, str(INTEGRATION_ROOT))

from ghidra_bridge import GhidraInstallation  # noqa: E402
from session import (  # noqa: E402
    EngineRemoteError,
    EngineStartupError,
    EngineTimeoutError,
    EngineTransportError,
    GhidraEngineSession,
    SERVER_SCRIPT_NAME,
    SessionLimits,
)


FAKE_WORKER = r'''from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import sys
import time

script_index = sys.argv.index("-postScript")
nonce = sys.argv[script_index + 2]
binary = Path(sys.argv[sys.argv.index("-import") + 1])
prefix = f"GHIDRAEX/1 {nonce} "

def emit(value):
    print(prefix + json.dumps(value, separators=(",", ":")), flush=True)

print("fake analyzeHeadless log noise", flush=True)
if binary.name == "no-ready":
    time.sleep(30)
    raise SystemExit(0)

context = {"programId": "fake-program", "revision": 0}
emit({
    "kind": "event", "version": 1, "event": "session.ready", "sequence": 1,
    "data": {
        "sessionId": "fake-session",
        "protocol": {"min": 1, "max": 1},
        "server": {"name": "fake-engine", "version": "0.1.0"},
        "ghidra": {"version": "test"},
        "context": context,
        "programSha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
    },
})

pending_methods = {}
for raw in sys.stdin:
    raw = raw.rstrip("\r\n")
    if not raw.startswith(prefix):
        continue
    request = json.loads(raw[len(prefix):])
    identifier = request["id"]
    method = request["method"]
    if method == "test.crash":
        os._exit(23)
    if method in {"test.hang", "test.ignore-cancel", "test.wait"}:
        pending_methods[identifier] = method
        continue
    if method == "test.noise":
        print("log contains GHIDRAEX/1 deliberately-wrong-nonce", flush=True)
        result = {"accepted": True}
    elif method == "test.fail":
        emit({
            "kind": "response", "version": 1, "id": identifier, "ok": False,
            "method": method,
            "error": {
                "code": "INVALID_PARAMS", "message": "fixture rejected params",
                "retryable": False, "details": {"field": "value"},
            },
        })
        continue
    elif method == "request.cancel":
        target = request.get("params", {}).get("targetId")
        pending_method = pending_methods.get(target)
        accepted = pending_method is not None
        if pending_method == "test.hang":
            emit({
                "kind": "response", "version": 1, "id": target, "ok": False,
                "method": pending_method,
                "error": {
                    "code": "REQUEST_CANCELLED", "message": "fixture request cancelled",
                    "retryable": True, "details": {},
                },
            })
            pending_methods.pop(target, None)
        result = {
            "accepted": accepted,
            "state": "requested" if accepted else "not-cancellable",
        }
    elif method == "session.close":
        emit({
            "kind": "response", "version": 1, "id": identifier, "ok": True,
            "method": method, "result": {"accepted": True},
        })
        emit({
            "kind": "event", "version": 1, "event": "session.closing", "sequence": 2,
            "data": {"reason": "requested"},
        })
        raise SystemExit(0)
    elif method == "session.hello":
        result = {
            "selectedVersion": 1,
            "sessionId": "fake-session",
            "server": {"name": "fake-engine", "version": "0.1.0"},
            "ghidra": {"version": "test"},
            "capabilities": {
                "methods": [
                    "session.ping", "session.close", "request.cancel",
                    "program.summary", "test.echo",
                ],
                "events": ["session.closing"],
                "transports": ["stdio"],
                "limits": {
                    "maxFrameBytes": 65536, "maxTimeoutMs": 30000,
                    "maxListingItems": 1000, "maxSymbolItems": 500,
                    "maxReferenceItems": 2000, "maxDecompileChars": 500000,
                },
            },
        }
    elif method == "program.summary":
        result = {
            "name": binary.name,
            "executableFormat": "fixture",
            "languageId": "x86:LE:64:default",
            "compilerSpecId": "gcc",
            "imageBase": "00400000",
            "minAddress": "00400000",
            "maxAddress": "0040ffff",
            "sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
            "analysis": {"complete": True, "timedOut": False},
            "memoryBlockCount": 2,
            "instructionCount": 17,
            "functionCount": 3,
            "symbolCount": 7,
        }
    else:
        result = request.get("params", {})
    emit({
        "kind": "response", "version": 1, "id": identifier, "ok": True,
        "method": method,
        **({"context": context} if method in {
            "program.summary", "listing.window", "symbols.search",
            "decompile.function", "references.list",
        } else {}),
        "result": result,
    })
'''


class PersistentSessionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="ghidraex-session-test-")
        root = Path(self.temporary.name)
        worker = root / "fake_worker.py"
        worker.write_text(FAKE_WORKER, encoding="utf-8")
        support = root / "support"
        support.mkdir()
        if os.name == "nt":
            launcher = support / "analyzeHeadless.bat"
            launcher.write_text(
                f'@echo off\r\n"{sys.executable}" "{worker}" %*\r\n', encoding="utf-8"
            )
        else:
            launcher = support / "analyzeHeadless"
            launcher.write_text(
                "#!/bin/sh\nexec "
                + shlex.quote(sys.executable)
                + " "
                + shlex.quote(str(worker))
                + ' "$@"\n',
                encoding="utf-8",
            )
            launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)
        self.installation = GhidraInstallation(root, launcher, None)
        self.scripts = root / "scripts"
        self.scripts.mkdir()
        (self.scripts / SERVER_SCRIPT_NAME).write_text("// fake script\n", encoding="utf-8")
        self.binary = root / "fixture.bin"
        self.binary.write_bytes(b"GhidraEx persistent session fixture")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def session(self, **overrides: object) -> GhidraEngineSession:
        values = {
            "startup_timeout": 3.0,
            "request_timeout": 1.0,
            "cancellation_grace": 0.25,
            "shutdown_timeout": 2.0,
            "analysis_timeout": 7,
            "max_cpu": 1,
            "max_frame_bytes": 65536,
            "retained_log_lines": 20,
        }
        values.update(overrides)
        return GhidraEngineSession(
            self.installation,
            self.binary,
            limits=SessionLimits(**values),
            script_directory=self.scripts,
        )

    def test_command_uses_import_analysis_and_persistent_post_script(self) -> None:
        session = self.session()
        command = session.build_command(Path("/tmp/project"), "project")

        self.assertIn("-import", command)
        self.assertIn("-analysisTimeoutPerFile", command)
        self.assertIn("-max-cpu", command)
        self.assertEqual(SERVER_SCRIPT_NAME, command[command.index("-postScript") + 1])
        self.assertEqual(session._nonce, command[command.index("-postScript") + 2])
        self.assertEqual("65536", command[command.index("-postScript") + 3])
        self.assertIn("-deleteProject", command)

    def test_ready_hello_round_trip_and_orderly_close(self) -> None:
        session = self.session().start()
        project = Path(session._project.name)
        try:
            self.assertEqual(
                {"programId": "fake-program", "revision": 0}, session.program_context
            )
            self.assertEqual(
                1,
                session.call("session.hello", {
                    "client": {"name": "test", "version": "1"},
                    "protocol": {"min": 1, "max": 1},
                    "requestedCapabilities": [],
                })["selectedVersion"],
            )
            self.assertIn("fake analyzeHeadless log noise", session.logs)
            self.assertEqual(17, session.call("program.summary")["instructionCount"])
        finally:
            session.close()

        self.assertFalse(session.is_alive)
        self.assertFalse(project.exists(), "owned temporary project survived session close")

    def test_remote_errors_keep_code_retryability_and_details(self) -> None:
        with self.session() as session:
            with self.assertRaises(EngineRemoteError) as raised:
                session.call("test.fail", {"value": 3})

        self.assertEqual("INVALID_PARAMS", raised.exception.code)
        self.assertFalse(raised.exception.retryable)
        self.assertEqual({"field": "value"}, raised.exception.details)

    def test_cooperative_cancellation_consumes_terminal_and_preserves_session(self) -> None:
        with self.session() as session:
            project = Path(session._project.name)
            with self.assertRaisesRegex(EngineTimeoutError, "terminated the request"):
                session.call("test.hang", timeout=0.1, request_id="slow")
            self.assertTrue(session.is_alive)
            self.assertTrue(project.exists())
            self.assertEqual(
                {"requestIdSafelyReused": True},
                session.call(
                    "test.echo",
                    {"requestIdSafelyReused": True},
                    request_id="slow",
                ),
            )
            self.assertEqual(
                1,
                session.call("session.hello", {
                    "client": {"name": "test", "version": "1"},
                    "protocol": {"min": 1, "max": 1},
                    "requestedCapabilities": [],
                })["selectedVersion"],
            )

    def test_ignored_cancellation_faults_concurrent_calls_and_cleans_project(self) -> None:
        session = self.session(cancellation_grace=0.1).start()
        project = Path(session._project.name)
        process = session._process
        concurrent_errors: list[BaseException] = []

        def wait_on_engine() -> None:
            try:
                session.call("test.wait", timeout=2.0, request_id="concurrent")
            except BaseException as error:
                concurrent_errors.append(error)

        waiter = threading.Thread(target=wait_on_engine, name="session-test-waiter")
        waiter.start()
        deadline = time.monotonic() + 1.0
        while time.monotonic() < deadline:
            with session._state_lock:
                if "concurrent" in session._pending:
                    break
            time.sleep(0.005)
        else:
            self.fail("concurrent fixture request was not registered")

        started = time.monotonic()
        try:
            with self.assertRaisesRegex(EngineTimeoutError, "session was invalidated"):
                session.call(
                    "test.ignore-cancel",
                    timeout=0.1,
                    request_id="ignored",
                )
            elapsed = time.monotonic() - started
            self.assertLess(elapsed, 1.5, "timeout escalation exceeded its bounded latency")
            waiter.join(timeout=1.0)
            self.assertFalse(waiter.is_alive())
            self.assertEqual(1, len(concurrent_errors))
            self.assertIsInstance(concurrent_errors[0], EngineTransportError)
            self.assertIn("ignored cancellation", str(concurrent_errors[0]))

            self.assertFalse(session.is_alive)
            self.assertIsNotNone(process)
            self.assertIsNotNone(process.poll(), "forced timeout escalation left worker alive")
            self.assertFalse(project.exists(), "faulted session retained its temporary project")
            self.assertIsNone(session._project)
            self.assertFalse(
                any("did not confirm worker exit" in line for line in session.logs),
                session.logs,
            )
            with self.assertRaises(EngineTransportError) as future_error:
                session.call("test.echo", {"after": "fault"})
            self.assertEqual(str(concurrent_errors[0]), str(future_error.exception))
        finally:
            session.close()
            waiter.join(timeout=1.0)

    def test_cancellation_grace_is_finite_and_bounded(self) -> None:
        for value in (0, 0.01, 5.01, float("nan"), float("inf"), True):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "cancellation_grace"):
                    SessionLimits(cancellation_grace=value)

    def test_per_call_timeout_is_finite_numeric_and_not_boolean(self) -> None:
        with self.session() as session:
            for value in (float("nan"), float("inf"), True, "1"):
                with self.subTest(value=value):
                    with self.assertRaisesRegex(ValueError, "request timeout"):
                        session.call("test.echo", timeout=value)

    def test_nonce_filters_log_lines_that_resemble_frames(self) -> None:
        with self.session() as session:
            self.assertEqual({"accepted": True}, session.call("test.noise"))
            self.assertTrue(
                any("deliberately-wrong-nonce" in line for line in session.logs), session.logs
            )

    def test_worker_crash_fails_the_inflight_request(self) -> None:
        session = self.session().start()
        try:
            with self.assertRaisesRegex(EngineTransportError, "status 23"):
                session.call("test.crash")
        finally:
            session.close()

    def test_oversized_request_is_rejected_before_write(self) -> None:
        with self.session() as session:
            with self.assertRaisesRegex(ValueError, "frame exceeds"):
                session.call("test.echo", {"blob": "x" * 66000})
            self.assertTrue(session.is_alive)

    def test_startup_deadline_terminates_worker_and_cleans_project(self) -> None:
        no_ready = self.binary.with_name("no-ready")
        no_ready.write_bytes(b"no ready event")
        session = GhidraEngineSession(
            self.installation,
            no_ready,
            limits=SessionLimits(
                startup_timeout=0.15,
                request_timeout=1,
                shutdown_timeout=1,
                max_frame_bytes=65536,
            ),
            script_directory=self.scripts,
        )
        with self.assertRaisesRegex(EngineStartupError, "session.ready"):
            session.start()
        self.assertFalse(session.is_alive)
        self.assertIsNone(session._project)


if __name__ == "__main__":
    unittest.main()
