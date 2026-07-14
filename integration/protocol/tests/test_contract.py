from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest


PROTOCOL_ROOT = Path(__file__).resolve().parents[1]
SCHEMA_ROOT = PROTOCOL_ROOT / "schemas"
sys.path.insert(0, str(PROTOCOL_ROOT))

from contract import (  # noqa: E402
    CORE_EVENTS,
    CORE_LIMITS,
    CORE_METHODS,
    FRAME_PREFIX,
    ContractViolation,
    authenticate_bearer_header,
    authorization_header,
    decode_frame,
    encode_frame,
    generate_gateway_token,
    generate_session_nonce,
    validate_event,
    validate_gateway_descriptor,
    validate_method_payload,
    validate_request,
    validate_response,
    validate_response_for_request,
)


CONTEXT = {"programId": "program-fixture", "revision": 0}
SESSION_NONCE = "0123456789abcdef0123456789abcdef"
OTHER_SESSION_NONCE = "fedcba9876543210fedcba9876543210"


REQUEST_PAYLOADS = {
    "session.hello": {
        "client": {"name": "contract-test", "version": "1.0.0", "instanceId": "test-1"},
        "protocol": {"min": 1, "max": 1},
        "requestedCapabilities": ["decompile.function", "references.list"],
    },
    "session.ping": {"nonce": "alive-1"},
    "session.close": {"reason": "test complete"},
    "request.cancel": {"targetId": "work-19"},
    "program.summary": {},
    "listing.window": {
        "start": "ram:00401000",
        "direction": "forward",
        "limit": 128,
        "includeBytes": True,
    },
    "symbols.search": {"query": "main", "kinds": ["function", "label"], "limit": 50},
    "decompile.function": {"address": "ram:00401000", "maxChars": 20_000},
    "references.list": {"address": "ram:00401000", "direction": "both", "limit": 100},
}


RESPONSE_PAYLOADS = {
    "session.hello": {
        "selectedVersion": 1,
        "sessionId": "session-fixture",
        "server": {"name": "ghidraex-engine", "version": "0.1.0"},
        "ghidra": {"version": "12.1.2"},
        "capabilities": {
            "methods": sorted(CORE_METHODS - {"session.hello"}),
            "events": sorted(CORE_EVENTS),
            "transports": ["stdio", "loopback-http"],
            "limits": dict(CORE_LIMITS),
        },
    },
    "session.ping": {"nonce": "alive-1", "monotonicTimeMs": 19, "state": "ready"},
    "session.close": {"accepted": True},
    "request.cancel": {"accepted": True, "state": "requested"},
    "program.summary": {
        "name": "tiny",
        "executableFormat": "Executable and Linking Format (ELF)",
        "languageId": "x86:LE:64:default",
        "compilerSpecId": "gcc",
        "imageBase": "ram:00400000",
        "minAddress": "ram:00400000",
        "maxAddress": "ram:00401fff",
        "sha256": "1" * 64,
        "analysis": {"complete": True, "timedOut": False},
        "memoryBlockCount": 4,
        "instructionCount": 19,
        "functionCount": 3,
        "symbolCount": 12,
    },
    "listing.window": {
        "items": [
            {
                "address": "ram:00401000",
                "length": 1,
                "kind": "instruction",
                "mnemonic": "PUSH",
                "operands": "RBP",
                "bytes": "55",
                "function": "main",
            }
        ],
        "nextCursor": "cursor-listing-2",
        "truncated": False,
    },
    "symbols.search": {
        "items": [
            {
                "name": "main",
                "address": "ram:00401000",
                "kind": "function",
                "namespace": "Global",
                "source": "ANALYSIS",
                "primary": True,
            }
        ],
        "truncated": False,
    },
    "decompile.function": {
        "function": {
            "name": "main",
            "entryPoint": "ram:00401000",
            "signature": "int main(void)",
        },
        "language": "c",
        "text": "int main(void) { return 0; }",
        "truncated": False,
        "warnings": [],
    },
    "references.list": {
        "items": [
            {
                "fromAddress": "ram:00401008",
                "toAddress": "ram:00401000",
                "type": "UNCONDITIONAL_CALL",
                "operandIndex": 0,
                "primary": True,
                "external": False,
            }
        ],
        "truncated": False,
    },
}


def request_for(method: str, request_id: str = "request-1") -> dict[str, object]:
    request: dict[str, object] = {
        "kind": "request",
        "version": 1,
        "id": request_id,
        "method": method,
        "params": REQUEST_PAYLOADS[method],
    }
    if method in {
        "program.summary",
        "listing.window",
        "symbols.search",
        "decompile.function",
        "references.list",
    }:
        request["context"] = dict(CONTEXT)
    if method in {"listing.window", "decompile.function", "references.list"}:
        request["timeoutMs"] = 5_000
    return request


def response_for(method: str, request_id: str = "request-1") -> dict[str, object]:
    response: dict[str, object] = {
        "kind": "response",
        "version": 1,
        "id": request_id,
        "method": method,
        "ok": True,
        "result": RESPONSE_PAYLOADS[method],
    }
    if method in {
        "program.summary",
        "listing.window",
        "symbols.search",
        "decompile.function",
        "references.list",
    }:
        response["context"] = dict(CONTEXT)
    return response


class CoreMethodContractTest(unittest.TestCase):
    def test_every_core_method_has_valid_request_and_response_samples(self) -> None:
        self.assertEqual(CORE_METHODS, frozenset(REQUEST_PAYLOADS))
        self.assertEqual(CORE_METHODS, frozenset(RESPONSE_PAYLOADS))

        for index, method in enumerate(sorted(CORE_METHODS)):
            with self.subTest(method=method):
                request = request_for(method, f"request-{index}")
                response = response_for(method, f"request-{index}")
                validate_request(request)
                validate_response(response)
                validate_response_for_request(request, response)
                self.assertEqual(
                    request,
                    decode_frame(encode_frame(request, SESSION_NONCE), SESSION_NONCE),
                )
                self.assertEqual(
                    response,
                    decode_frame(encode_frame(response, SESSION_NONCE), SESSION_NONCE),
                )

    def test_missing_program_context_is_rejected(self) -> None:
        request = request_for("listing.window")
        del request["context"]

        with self.assertRaisesRegex(ContractViolation, "context.*required"):
            validate_request(request)

    def test_context_is_forbidden_on_session_methods(self) -> None:
        request = request_for("session.ping")
        request["context"] = dict(CONTEXT)

        with self.assertRaisesRegex(ContractViolation, "context.*forbidden"):
            validate_request(request)

    def test_listing_requires_exactly_one_start_mechanism(self) -> None:
        request = request_for("listing.window")
        request["params"] = {"start": "ram:1", "cursor": "cursor-1"}

        with self.assertRaisesRegex(ContractViolation, "exactly one"):
            validate_request(request)

    def test_method_limit_is_rejected_before_work_starts(self) -> None:
        request = request_for("references.list")
        request["params"] = {"address": "ram:1", "limit": 2_001}

        with self.assertRaises(ContractViolation) as caught:
            validate_request(request)
        self.assertEqual("LIMIT_EXCEEDED", caught.exception.code)

    def test_unknown_top_level_field_is_rejected(self) -> None:
        request = request_for("session.ping")
        request["debug"] = True

        with self.assertRaisesRegex(ContractViolation, "unknown field"):
            validate_request(request)

    def test_boolean_is_not_accepted_as_an_integer_version(self) -> None:
        request = request_for("session.ping")
        request["version"] = True

        with self.assertRaises(ContractViolation) as caught:
            validate_request(request)
        self.assertEqual("UNSUPPORTED_VERSION", caught.exception.code)

    def test_successful_program_response_must_echo_context(self) -> None:
        response = response_for("program.summary")
        del response["context"]

        with self.assertRaisesRegex(ContractViolation, "context.*required"):
            validate_response(response)

    def test_response_correlation_detects_program_and_revision_mismatch(self) -> None:
        request = request_for("program.summary")
        wrong_program = response_for("program.summary")
        wrong_program["context"] = {"programId": "another", "revision": 0}
        with self.assertRaises(ContractViolation) as caught:
            validate_response_for_request(request, wrong_program)
        self.assertEqual("PROGRAM_MISMATCH", caught.exception.code)

        wrong_revision = response_for("program.summary")
        wrong_revision["context"] = {"programId": "program-fixture", "revision": 9}
        with self.assertRaises(ContractViolation) as caught:
            validate_response_for_request(request, wrong_revision)
        self.assertEqual("REVISION_MISMATCH", caught.exception.code)

    def test_error_response_has_no_result_and_uses_stable_taxonomy(self) -> None:
        response = {
            "kind": "response",
            "version": 1,
            "id": "request-1",
            "method": "decompile.function",
            "ok": False,
            "error": {
                "code": "DEADLINE_EXCEEDED",
                "message": "decompiler exceeded 5 seconds",
                "retryable": True,
                "retryAfterMs": 250,
                "details": {"phase": "decompile"},
            },
        }

        validate_response(response)
        response["result"] = {}
        with self.assertRaisesRegex(ContractViolation, "unknown field"):
            validate_response(response)

    def test_extension_envelope_is_forwardable_but_not_a_core_payload(self) -> None:
        request = {
            "kind": "request",
            "version": 1,
            "id": "experimental-1",
            "method": "x.example.peek",
            "params": {},
        }
        validate_request(request)
        with self.assertRaises(ContractViolation) as caught:
            validate_method_payload("x.example.peek", "request", {})
        self.assertEqual("METHOD_NOT_FOUND", caught.exception.code)

    def test_inverted_protocol_range_is_rejected(self) -> None:
        request = request_for("session.hello")
        request["params"] = {
            "client": {"name": "test", "version": "1"},
            "protocol": {"min": 2, "max": 1},
        }
        with self.assertRaisesRegex(ContractViolation, "min must not exceed max"):
            validate_request(request)


class EventContractTest(unittest.TestCase):
    def test_ready_progress_warning_and_closing_events(self) -> None:
        events = [
            {
                "kind": "event",
                "version": 1,
                "event": "session.ready",
                "sequence": 1,
                "data": {
                    "sessionId": "session-fixture",
                    "protocol": {"min": 1, "max": 1},
                    "server": {"name": "ghidraex-engine", "version": "0.1.0"},
                    "ghidra": {"version": "12.1.2"},
                    "context": dict(CONTEXT),
                    "programSha256": "1" * 64,
                },
            },
            {
                "kind": "event",
                "version": 1,
                "event": "analysis.progress",
                "sequence": 2,
                "context": dict(CONTEXT),
                "data": {"phase": "analysis", "completed": 5, "total": 10},
            },
            {
                "kind": "event",
                "version": 1,
                "event": "engine.warning",
                "sequence": 3,
                "data": {"code": "ANALYSIS_TIMEOUT", "message": "partial analysis"},
            },
            {
                "kind": "event",
                "version": 1,
                "event": "session.closing",
                "sequence": 4,
                "data": {"reason": "requested"},
            },
        ]
        for event in events:
            with self.subTest(event=event["event"]):
                validate_event(event)
                self.assertEqual(
                    event,
                    decode_frame(encode_frame(event, SESSION_NONCE), SESSION_NONCE),
                )

    def test_progress_cannot_claim_more_than_total(self) -> None:
        event = {
            "kind": "event",
            "version": 1,
            "event": "analysis.progress",
            "sequence": 2,
            "context": dict(CONTEXT),
            "data": {"phase": "analysis", "completed": 11, "total": 10},
        }
        with self.assertRaisesRegex(ContractViolation, "completed must not exceed total"):
            validate_event(event)

    def test_ready_event_cryptographically_binds_the_launch_program(self) -> None:
        event = {
            "kind": "event",
            "version": 1,
            "event": "session.ready",
            "sequence": 1,
            "data": {
                "sessionId": "session-fixture",
                "protocol": {"min": 1, "max": 1},
                "server": {"name": "ghidraex-engine", "version": "0.1.0"},
                "ghidra": {"version": "12.1.2"},
                "context": dict(CONTEXT),
                "programSha256": "a" * 64,
            },
        }
        validate_event(event)

        del event["data"]["programSha256"]
        with self.assertRaisesRegex(ContractViolation, "programSha256"):
            validate_event(event)

        event["data"]["programSha256"] = "A" * 64
        with self.assertRaisesRegex(ContractViolation, "invalid format"):
            validate_event(event)


class FramingContractTest(unittest.TestCase):
    def test_frame_nonce_is_required_exact_and_session_scoped(self) -> None:
        request = request_for("session.ping")
        frame = encode_frame(request, SESSION_NONCE)

        with self.assertRaises(TypeError):
            encode_frame(request)  # type: ignore[call-arg]
        with self.assertRaises(TypeError):
            decode_frame(frame)  # type: ignore[call-arg]
        with self.assertRaisesRegex(ContractViolation, "does not match"):
            decode_frame(frame, OTHER_SESSION_NONCE)
        with self.assertRaisesRegex(ContractViolation, "invalid format"):
            encode_frame(request, SESSION_NONCE.upper())
        with self.assertRaisesRegex(ContractViolation, "between 32 and 32"):
            encode_frame(request, "abcd")

        nonce = generate_session_nonce()
        self.assertRegex(nonce, r"^[0-9a-f]{32}$")
        self.assertNotEqual(nonce, generate_session_nonce())

    def test_frame_rejects_a_malformed_wire_nonce(self) -> None:
        valid = encode_frame(request_for("session.ping"), SESSION_NONCE)
        json_and_lf = valid[len(FRAME_PREFIX) + 33 :]
        malformed = FRAME_PREFIX + SESSION_NONCE.upper().encode("ascii") + b" " + json_and_lf
        with self.assertRaisesRegex(ContractViolation, "invalid format"):
            decode_frame(malformed, SESSION_NONCE)

    def test_duplicate_json_keys_are_rejected(self) -> None:
        frame = (
            FRAME_PREFIX
            + SESSION_NONCE.encode("ascii")
            + b" "
            + b'{"kind":"request","kind":"request","version":1,'
            + b'"id":"r1","method":"session.ping","params":{}}\n'
        )
        with self.assertRaisesRegex(ContractViolation, "duplicate JSON object key"):
            decode_frame(frame, SESSION_NONCE)

    def test_nonfinite_json_number_is_rejected(self) -> None:
        frame = (
            FRAME_PREFIX
            + SESSION_NONCE.encode("ascii")
            + b" "
            + b'{"kind":"event","version":1,"event":"x.example.event",'
            + b'"sequence":1,"data":{"value":NaN}}\n'
        )
        with self.assertRaisesRegex(ContractViolation, "non-finite"):
            decode_frame(frame, SESSION_NONCE)

    def test_prefix_utf8_and_lf_are_strict(self) -> None:
        valid = encode_frame(request_for("session.ping"), SESSION_NONCE)
        with self.assertRaisesRegex(ContractViolation, "prefix"):
            decode_frame(valid[len(FRAME_PREFIX) :], SESSION_NONCE)
        with self.assertRaisesRegex(ContractViolation, "one LF"):
            decode_frame(valid[:-1] + b"\r\n", SESSION_NONCE)
        with self.assertRaisesRegex(ContractViolation, "UTF-8"):
            decode_frame(
                FRAME_PREFIX + SESSION_NONCE.encode("ascii") + b" \xff\n",
                SESSION_NONCE,
            )

    def test_configured_frame_cap_is_enforced_for_encode_and_decode(self) -> None:
        frame = encode_frame(request_for("session.hello"), SESSION_NONCE)
        with self.assertRaises(ContractViolation) as caught:
            encode_frame(request_for("session.hello"), SESSION_NONCE, max_frame_bytes=100)
        self.assertEqual("LIMIT_EXCEEDED", caught.exception.code)
        with self.assertRaises(ContractViolation) as caught:
            decode_frame(frame, SESSION_NONCE, max_frame_bytes=100)
        self.assertEqual("LIMIT_EXCEEDED", caught.exception.code)


class GatewaySecurityContractTest(unittest.TestCase):
    def test_tokens_have_at_least_256_bits_and_use_constant_shape(self) -> None:
        first = generate_gateway_token()
        second = generate_gateway_token()
        self.assertNotEqual(first, second)
        self.assertGreaterEqual(len(first), 43)
        header = authorization_header(first)
        self.assertTrue(authenticate_bearer_header(header, first))
        self.assertFalse(authenticate_bearer_header(header, second))
        self.assertFalse(authenticate_bearer_header(f"bearer {first}", first))
        self.assertFalse(authenticate_bearer_header(None, first))

    def test_gateway_descriptor_accepts_only_origin_level_loopback_urls(self) -> None:
        token = generate_gateway_token()
        for base_url in ("http://127.0.0.1:49152", "http://[::1]:49152"):
            with self.subTest(base_url=base_url):
                validate_gateway_descriptor(
                    {"baseUrl": base_url, "token": token, "protocolVersion": 1, "pid": 42}
                )

        for base_url in (
            "http://localhost:49152",
            "http://0.0.0.0:49152",
            "https://127.0.0.1:49152",
            "http://127.0.0.1:49152/v1",
            "http://127.0.0.1:99999",
        ):
            with self.subTest(base_url=base_url):
                with self.assertRaises(ContractViolation):
                    validate_gateway_descriptor(
                        {"baseUrl": base_url, "token": token, "protocolVersion": 1, "pid": 42}
                    )


class SchemaIntegrityTest(unittest.TestCase):
    def test_all_schemas_are_json_2020_12_with_unique_ids_and_resolvable_refs(self) -> None:
        documents: dict[Path, object] = {}
        ids: set[str] = set()
        for path in sorted(SCHEMA_ROOT.glob("*.schema.json")):
            document = json.loads(path.read_text(encoding="utf-8"))
            documents[path] = document
            self.assertEqual("https://json-schema.org/draft/2020-12/schema", document["$schema"])
            self.assertNotIn(document["$id"], ids)
            ids.add(document["$id"])

        self.assertGreaterEqual(len(documents), 8)
        for path, document in documents.items():
            for ref in self._collect_refs(document):
                self._assert_ref_resolves(path, ref, documents)

    def test_core_method_schema_covers_every_request_and_success_result(self) -> None:
        document = json.loads((SCHEMA_ROOT / "core-methods.schema.json").read_text(encoding="utf-8"))
        pairs = {
            (
                branch["properties"]["method"]["const"],
                branch["properties"]["direction"]["const"],
            )
            for branch in document["oneOf"]
        }
        expected = {(method, direction) for method in CORE_METHODS for direction in ("request", "response")}
        self.assertEqual(expected, pairs)

    def test_core_event_schema_covers_every_core_event(self) -> None:
        document = json.loads((SCHEMA_ROOT / "core-events.schema.json").read_text(encoding="utf-8"))
        names = {branch["properties"]["event"]["const"] for branch in document["oneOf"]}
        self.assertEqual(CORE_EVENTS, names)

    @staticmethod
    def _collect_refs(value: object) -> list[str]:
        refs: list[str] = []
        if isinstance(value, dict):
            for key, item in value.items():
                if key == "$ref" and isinstance(item, str):
                    refs.append(item)
                else:
                    refs.extend(SchemaIntegrityTest._collect_refs(item))
        elif isinstance(value, list):
            for item in value:
                refs.extend(SchemaIntegrityTest._collect_refs(item))
        return refs

    def _assert_ref_resolves(
        self, origin: Path, ref: str, documents: dict[Path, object]
    ) -> None:
        file_part, separator, fragment = ref.partition("#")
        target_path = (origin.parent / file_part).resolve() if file_part else origin.resolve()
        normalized_documents = {path.resolve(): value for path, value in documents.items()}
        self.assertIn(target_path, normalized_documents, f"missing schema target for {origin}: {ref}")
        target = normalized_documents[target_path]
        if not separator or not fragment:
            return
        self.assertTrue(fragment.startswith("/"), f"unsupported JSON pointer in {ref}")
        for escaped in fragment[1:].split("/"):
            key = escaped.replace("~1", "/").replace("~0", "~")
            self.assertIsInstance(target, dict, f"non-object JSON pointer target in {ref}")
            self.assertIn(key, target, f"missing JSON pointer segment {key!r} in {ref}")
            target = target[key]


if __name__ == "__main__":
    unittest.main()
