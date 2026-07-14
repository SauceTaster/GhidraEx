from __future__ import annotations

import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


TEST_ROOT = Path(__file__).resolve().parent
INTEGRATION_ROOT = TEST_ROOT.parent
REPOSITORY_ROOT = INTEGRATION_ROOT.parents[1]
sys.path.insert(0, str(REPOSITORY_ROOT))

from integration.ghidra.ghidra_bridge import (  # noqa: E402
    GhidraInstallation,
    GhidraUnavailableError,
)
from integration.ghidra.session import GhidraEngineSession, SessionLimits  # noqa: E402
from integration.gateway.gateway import GatewayLimits, GhidraGateway  # noqa: E402
from integration.protocol.contract import validate_event, validate_method_payload  # noqa: E402


class RealPersistentGhidraSessionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.installation = GhidraInstallation.discover()
        except GhidraUnavailableError as error:
            raise unittest.SkipTest(str(error)) from error
        cls.compiler = shutil.which(os.environ.get("CC", "cc"))
        if cls.compiler is None:
            raise unittest.SkipTest("a native C compiler is required to build fixtures/tiny.c")

    def test_real_worker_paging_search_references_decompile_and_cleanup(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ghidraex-session-fixture-") as temporary:
            fixture = Path(temporary) / ("tiny.exe" if os.name == "nt" else "tiny")
            compile_result = subprocess.run(
                [
                    self.compiler,
                    "-O0",
                    "-g0",
                    str(INTEGRATION_ROOT / "fixtures" / "tiny.c"),
                    "-o",
                    str(fixture),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if compile_result.returncode != 0:
                self.fail(f"fixture compilation failed:\n{compile_result.stdout}")

            expected_sha256 = hashlib.sha256(fixture.read_bytes()).hexdigest()
            session = GhidraEngineSession(
                self.installation,
                fixture,
                limits=SessionLimits(
                    startup_timeout=600,
                    request_timeout=30,
                    shutdown_timeout=10,
                    analysis_timeout=300,
                    max_cpu=2,
                    max_frame_bytes=65_536,
                ),
            ).start()
            project_path = Path(session._project.name)
            try:
                validate_event(dict(session.ready_event))
                self.assertEqual(expected_sha256, session.ready_event["data"]["programSha256"])
                self.assertEqual(0, session.program_context["revision"])
                methods = session.negotiated_capabilities["methods"]
                for method in (
                    "program.summary",
                    "listing.window",
                    "symbols.search",
                    "references.list",
                    "decompile.function",
                ):
                    self.assertIn(method, methods)

                summary = session.call("program.summary")
                validate_method_payload("program.summary", "response", summary)
                self.assertEqual(expected_sha256, summary["sha256"])
                self.assertTrue(summary["analysis"]["complete"])
                self.assertFalse(summary["analysis"]["timedOut"])
                self.assertGreater(summary["instructionCount"], 0)
                self.assertGreater(summary["functionCount"], 0)

                symbols = session.call(
                    "symbols.search", {"query": "main", "limit": 20}
                )
                validate_method_payload("symbols.search", "response", symbols)
                self.assertTrue(symbols["items"], "real symbol search returned no main-like item")
                function_symbol = next(
                    (item for item in symbols["items"] if item["kind"] == "function"),
                    symbols["items"][0],
                )
                address = function_symbol["address"]

                first_page = session.call(
                    "listing.window",
                    {"start": address, "direction": "forward", "limit": 5, "includeBytes": True},
                )
                validate_method_payload("listing.window", "response", first_page)
                self.assertTrue(first_page["items"])
                self.assertLessEqual(len(first_page["items"]), 5)
                if first_page["truncated"]:
                    second_page = session.call(
                        "listing.window",
                        {
                            "cursor": first_page["nextCursor"],
                            "direction": "forward",
                            "limit": 5,
                            "includeBytes": True,
                        },
                    )
                    validate_method_payload("listing.window", "response", second_page)
                    self.assertTrue(second_page["items"])
                    self.assertTrue(
                        {item["address"] for item in first_page["items"]}.isdisjoint(
                            item["address"] for item in second_page["items"]
                        )
                    )

                references = session.call(
                    "references.list",
                    {"address": address, "direction": "both", "limit": 64},
                )
                validate_method_payload("references.list", "response", references)

                decompiled = session.call(
                    "decompile.function",
                    {"address": address, "maxChars": 20_000},
                    timeout=30,
                )
                validate_method_payload("decompile.function", "response", decompiled)
                self.assertTrue(decompiled["text"].strip())
                self.assertEqual("c", decompiled["language"])

                ping = session.call("session.ping", {"nonce": "real-session"})
                validate_method_payload("session.ping", "response", ping)
                self.assertEqual("real-session", ping["nonce"])

                gateway = GhidraGateway(
                    session,
                    limits=GatewayLimits(
                        event_stream_seconds=0.25,
                        event_poll_seconds=0.02,
                    ),
                ).start()
                try:
                    connection = http.client.HTTPConnection(
                        "127.0.0.1", gateway.port, timeout=2
                    )
                    connection.request(
                        "GET",
                        "/v1/events?after=0",
                        headers={
                            "Host": gateway.authority,
                            "Authorization": f"Bearer {gateway.token}",
                        },
                    )
                    event_response = connection.getresponse()
                    self.assertEqual(200, event_response.status)
                    self.assertEqual(
                        "application/x-ndjson; charset=utf-8",
                        event_response.getheader("Content-Type"),
                    )
                    replayed_ready = json.loads(event_response.readline())
                    validate_event(replayed_ready)
                    self.assertEqual(session.program_context, replayed_ready["data"]["context"])
                    connection.close()

                    summary_request = {
                        "kind": "request",
                        "version": 1,
                        "id": "real-http-summary",
                        "method": "program.summary",
                        "context": dict(session.program_context),
                        "timeoutMs": 5_000,
                        "params": {},
                    }
                    body = json.dumps(summary_request, separators=(",", ":"))
                    connection = http.client.HTTPConnection(
                        "127.0.0.1", gateway.port, timeout=5
                    )
                    connection.request(
                        "POST",
                        "/v1/requests",
                        body=body,
                        headers={
                            "Host": gateway.authority,
                            "Authorization": f"Bearer {gateway.token}",
                            "Content-Type": "application/json",
                        },
                    )
                    response = connection.getresponse()
                    payload = json.loads(response.read())
                    connection.close()
                    self.assertEqual(200, response.status)
                    self.assertTrue(payload["ok"])
                    self.assertEqual(expected_sha256, payload["result"]["sha256"])
                    self.assertEqual(session.program_context, payload["context"])
                finally:
                    gateway.close()
            finally:
                session.close()

            self.assertFalse(session.is_alive)
            self.assertFalse(project_path.exists(), "real temporary project survived session close")


if __name__ == "__main__":
    unittest.main()
