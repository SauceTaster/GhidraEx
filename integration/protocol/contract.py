#!/usr/bin/env python3
"""Dependency-free reference codec and runtime contract for GhidraEx protocol v1.

JSON Schema is the language-neutral source of truth.  This module supplies the safety checks that
are awkward or impossible to express portably in JSON Schema: duplicate-key rejection, framed
stdio limits, context/method coupling, protocol-range ordering, constant-time bearer-token checks,
and loopback URL validation.
"""

from __future__ import annotations

import hmac
import json
import math
import re
import secrets
from typing import Any, Callable, Mapping, NoReturn
from urllib.parse import urlsplit


PROTOCOL_VERSION = 1
FRAME_PREFIX = b"GHIDRAEX/1 "
MAX_FRAME_BYTES = 2_097_152
MAX_TIMEOUT_MS = 30_000
MAX_JSON_DEPTH = 32
MAX_JSON_COLLECTION_ITEMS = 2_000

CORE_LIMITS = {
    "maxFrameBytes": MAX_FRAME_BYTES,
    "maxTimeoutMs": MAX_TIMEOUT_MS,
    "maxListingItems": 1_000,
    "maxSymbolItems": 500,
    "maxReferenceItems": 2_000,
    "maxDecompileChars": 500_000,
}
CORE_DEFAULTS = {
    "timeoutMs": 5_000,
    "listingDirection": "forward",
    "listingLimit": 128,
    "listingIncludeBytes": False,
    "symbolLimit": 100,
    "decompileMaxChars": 200_000,
    "referenceDirection": "both",
    "referenceLimit": 256,
}

CORE_METHODS = frozenset(
    {
        "session.hello",
        "session.ping",
        "session.close",
        "request.cancel",
        "program.summary",
        "listing.window",
        "symbols.search",
        "decompile.function",
        "references.list",
    }
)
PROGRAM_METHODS = frozenset(
    {
        "program.summary",
        "listing.window",
        "symbols.search",
        "decompile.function",
        "references.list",
    }
)
CORE_EVENTS = frozenset(
    {"session.ready", "analysis.progress", "engine.warning", "session.closing"}
)
ERROR_CODES = frozenset(
    {
        "INVALID_REQUEST",
        "UNSUPPORTED_VERSION",
        "AUTH_FAILED",
        "METHOD_NOT_FOUND",
        "INVALID_PARAMS",
        "SESSION_NOT_READY",
        "PROGRAM_MISMATCH",
        "REVISION_MISMATCH",
        "NOT_FOUND",
        "LIMIT_EXCEEDED",
        "DEADLINE_EXCEEDED",
        "REQUEST_CANCELLED",
        "ENGINE_BUSY",
        "ENGINE_UNAVAILABLE",
        "GHIDRA_FAILURE",
        "INTERNAL",
    }
)

_REQUEST_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_METHOD_NAME = re.compile(r"^[a-z][a-z0-9-]*(?:\.[a-z][a-z0-9-]*)+$")
_WARNING_CODE = re.compile(r"^[A-Z][A-Z0-9_]*$")
_HEX_BYTES = re.compile(r"^(?:[0-9a-f]{2})*$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_TOKEN = re.compile(r"^[A-Za-z0-9_-]+$")
_SESSION_NONCE = re.compile(r"^[0-9a-f]{32}$")


class ContractViolation(ValueError):
    """A bounded, path-addressed protocol validation failure."""

    def __init__(self, code: str, path: str, message: str) -> None:
        self.code = code
        self.path = path
        self.message = message
        super().__init__(f"{code} at {path}: {message}")


def _fail(path: str, message: str, code: str = "INVALID_REQUEST") -> NoReturn:
    raise ContractViolation(code, path, message)


def _object(value: Any, path: str, code: str = "INVALID_REQUEST") -> dict[str, Any]:
    if not isinstance(value, dict):
        _fail(path, "must be an object", code)
    return value


def _exact_keys(
    value: Mapping[str, Any],
    path: str,
    *,
    required: set[str] | frozenset[str],
    optional: set[str] | frozenset[str] = frozenset(),
    code: str = "INVALID_REQUEST",
) -> None:
    keys = set(value)
    missing = required - keys
    unknown = keys - required - optional
    if missing:
        _fail(path, f"missing required field(s): {', '.join(sorted(missing))}", code)
    if unknown:
        _fail(path, f"unknown field(s): {', '.join(sorted(unknown))}", code)


def _string(
    value: Any,
    path: str,
    *,
    minimum: int = 0,
    maximum: int,
    pattern: re.Pattern[str] | None = None,
    code: str = "INVALID_PARAMS",
) -> str:
    if not isinstance(value, str):
        _fail(path, "must be a string", code)
    if not minimum <= len(value) <= maximum:
        _fail(path, f"length must be between {minimum} and {maximum}", code)
    if pattern is not None and pattern.fullmatch(value) is None:
        _fail(path, "has an invalid format", code)
    return value


def _integer(
    value: Any,
    path: str,
    *,
    minimum: int,
    maximum: int | None = None,
    code: str = "INVALID_PARAMS",
) -> int:
    if type(value) is not int:
        _fail(path, "must be an integer", code)
    if value < minimum or (maximum is not None and value > maximum):
        suffix = f" through {maximum}" if maximum is not None else f" or greater"
        _fail(path, f"must be {minimum}{suffix}", code)
    return value


def _boolean(value: Any, path: str, code: str = "INVALID_PARAMS") -> bool:
    if type(value) is not bool:
        _fail(path, "must be a boolean", code)
    return value


def _array(
    value: Any,
    path: str,
    *,
    maximum: int,
    minimum: int = 0,
    code: str = "INVALID_PARAMS",
) -> list[Any]:
    if not isinstance(value, list):
        _fail(path, "must be an array", code)
    if not minimum <= len(value) <= maximum:
        _fail(path, f"must contain between {minimum} and {maximum} items", code)
    return value


def _enum(value: Any, path: str, choices: frozenset[str] | set[str], code: str = "INVALID_PARAMS") -> str:
    if not isinstance(value, str) or value not in choices:
        _fail(path, f"must be one of: {', '.join(sorted(choices))}", code)
    return value


def _request_id(value: Any, path: str, code: str = "INVALID_REQUEST") -> str:
    return _string(value, path, minimum=1, maximum=128, pattern=_REQUEST_ID, code=code)


def _method_name(value: Any, path: str, code: str = "INVALID_REQUEST") -> str:
    return _string(value, path, minimum=3, maximum=128, pattern=_METHOD_NAME, code=code)


def _address(value: Any, path: str) -> str:
    return _string(value, path, minimum=1, maximum=128)


def _cursor(value: Any, path: str) -> str:
    return _string(value, path, minimum=1, maximum=1024)


def _session_nonce(value: Any, path: str = "session_nonce") -> str:
    return _string(
        value,
        path,
        minimum=32,
        maximum=32,
        pattern=_SESSION_NONCE,
        code="INVALID_REQUEST",
    )


def _check_json_shape(value: Any, path: str = "$", depth: int = 0) -> None:
    if depth > MAX_JSON_DEPTH:
        _fail(path, f"JSON nesting exceeds {MAX_JSON_DEPTH}")
    if value is None or type(value) is bool:
        return
    if type(value) is int:
        if not -(2**63) <= value <= 2**63 - 1:
            _fail(path, "integer is outside the signed 64-bit range")
        return
    if type(value) is float:
        if not math.isfinite(value):
            _fail(path, "non-finite numbers are forbidden")
        return
    if isinstance(value, str):
        if len(value.encode("utf-8")) > MAX_FRAME_BYTES:
            _fail(path, "string exceeds the frame bound")
        return
    if isinstance(value, list):
        if len(value) > MAX_JSON_COLLECTION_ITEMS:
            _fail(path, f"array exceeds {MAX_JSON_COLLECTION_ITEMS} items")
        for index, item in enumerate(value):
            _check_json_shape(item, f"{path}[{index}]", depth + 1)
        return
    if isinstance(value, dict):
        if len(value) > 128:
            _fail(path, "object exceeds 128 fields")
        for key, item in value.items():
            if not isinstance(key, str):
                _fail(path, "object keys must be strings")
            if len(key) > 256:
                _fail(path, "object key exceeds 256 characters")
            _check_json_shape(item, f"{path}.{key}", depth + 1)
        return
    _fail(path, f"unsupported JSON value type: {type(value).__name__}")


def _validate_context(value: Any, path: str) -> None:
    context = _object(value, path)
    _exact_keys(context, path, required={"programId", "revision"})
    _string(context["programId"], f"{path}.programId", minimum=1, maximum=128)
    _integer(context["revision"], f"{path}.revision", minimum=0)


def _validate_identity(value: Any, path: str) -> None:
    identity = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        identity,
        path,
        required={"name", "version"},
        optional={"instanceId"},
        code="INVALID_PARAMS",
    )
    _string(identity["name"], f"{path}.name", minimum=1, maximum=128)
    _string(identity["version"], f"{path}.version", minimum=1, maximum=128)
    if "instanceId" in identity:
        _string(identity["instanceId"], f"{path}.instanceId", minimum=1, maximum=128)


def _validate_protocol_range(value: Any, path: str) -> None:
    protocol = _object(value, path, "INVALID_PARAMS")
    _exact_keys(protocol, path, required={"min", "max"}, code="INVALID_PARAMS")
    minimum = _integer(protocol["min"], f"{path}.min", minimum=1)
    maximum = _integer(protocol["max"], f"{path}.max", minimum=1)
    if minimum > maximum:
        _fail(path, "min must not exceed max", "INVALID_PARAMS")


def _validate_limits(value: Any, path: str) -> None:
    limits = _object(value, path, "INVALID_PARAMS")
    _exact_keys(limits, path, required=set(CORE_LIMITS), code="INVALID_PARAMS")
    _integer(limits["maxFrameBytes"], f"{path}.maxFrameBytes", minimum=65_536, maximum=MAX_FRAME_BYTES)
    _integer(limits["maxTimeoutMs"], f"{path}.maxTimeoutMs", minimum=100, maximum=MAX_TIMEOUT_MS)
    _integer(limits["maxListingItems"], f"{path}.maxListingItems", minimum=1, maximum=1_000)
    _integer(limits["maxSymbolItems"], f"{path}.maxSymbolItems", minimum=1, maximum=500)
    _integer(limits["maxReferenceItems"], f"{path}.maxReferenceItems", minimum=1, maximum=2_000)
    _integer(limits["maxDecompileChars"], f"{path}.maxDecompileChars", minimum=1_024, maximum=500_000)


def _validate_capabilities(value: Any, path: str) -> None:
    capabilities = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        capabilities,
        path,
        required={"methods", "events", "transports", "limits"},
        code="INVALID_PARAMS",
    )
    methods = _array(capabilities["methods"], f"{path}.methods", maximum=128)
    normalized_methods = [_method_name(item, f"{path}.methods[{index}]", "INVALID_PARAMS") for index, item in enumerate(methods)]
    if len(normalized_methods) != len(set(normalized_methods)):
        _fail(f"{path}.methods", "items must be unique", "INVALID_PARAMS")
    events = _array(capabilities["events"], f"{path}.events", maximum=128)
    normalized_events = [_method_name(item, f"{path}.events[{index}]", "INVALID_PARAMS") for index, item in enumerate(events)]
    if len(normalized_events) != len(set(normalized_events)):
        _fail(f"{path}.events", "items must be unique", "INVALID_PARAMS")
    transports = _array(capabilities["transports"], f"{path}.transports", minimum=1, maximum=2)
    normalized_transports = [
        _enum(item, f"{path}.transports[{index}]", {"stdio", "loopback-http"})
        for index, item in enumerate(transports)
    ]
    if len(normalized_transports) != len(set(normalized_transports)):
        _fail(f"{path}.transports", "items must be unique", "INVALID_PARAMS")
    _validate_limits(capabilities["limits"], f"{path}.limits")


def _validate_error(value: Any, path: str) -> None:
    error = _object(value, path)
    _exact_keys(
        error,
        path,
        required={"code", "message", "retryable"},
        optional={"retryAfterMs", "details"},
    )
    _enum(error["code"], f"{path}.code", ERROR_CODES, "INVALID_REQUEST")
    _string(error["message"], f"{path}.message", minimum=1, maximum=2048, code="INVALID_REQUEST")
    _boolean(error["retryable"], f"{path}.retryable", "INVALID_REQUEST")
    if "retryAfterMs" in error:
        _integer(error["retryAfterMs"], f"{path}.retryAfterMs", minimum=0, maximum=MAX_TIMEOUT_MS, code="INVALID_REQUEST")
    if "details" in error:
        details = _object(error["details"], f"{path}.details")
        if len(details) > 32:
            _fail(f"{path}.details", "must contain no more than 32 fields")


def validate_request(value: Any) -> None:
    """Validate a complete request envelope and any recognized core method payload."""

    _check_json_shape(value)
    request = _object(value, "$")
    _exact_keys(
        request,
        "$",
        required={"kind", "version", "id", "method", "params"},
        optional={"context", "timeoutMs"},
    )
    if request["kind"] != "request":
        _fail("$.kind", "must equal 'request'")
    if type(request["version"]) is not int or request["version"] != PROTOCOL_VERSION:
        _fail("$.version", "must equal protocol version 1", "UNSUPPORTED_VERSION")
    _request_id(request["id"], "$.id")
    method = _method_name(request["method"], "$.method")
    _object(request["params"], "$.params")
    if "timeoutMs" in request:
        _integer(request["timeoutMs"], "$.timeoutMs", minimum=100, maximum=MAX_TIMEOUT_MS, code="LIMIT_EXCEEDED")
    if "context" in request:
        _validate_context(request["context"], "$.context")

    if method in PROGRAM_METHODS and "context" not in request:
        _fail("$.context", f"is required for {method}")
    if method in CORE_METHODS - PROGRAM_METHODS and "context" in request:
        _fail("$.context", f"is forbidden for {method}")
    if method in CORE_METHODS:
        validate_method_payload(method, "request", request["params"])


def validate_response(value: Any) -> None:
    """Validate a complete response envelope and successful core method result."""

    _check_json_shape(value)
    response = _object(value, "$")
    base = {"kind", "version", "id", "method", "ok"}
    if response.get("ok") is True:
        required = base | {"result"}
        optional = {"context"}
    elif response.get("ok") is False:
        required = base | {"error"}
        optional = {"context"}
    else:
        _fail("$.ok", "must be a boolean")
    _exact_keys(response, "$", required=required, optional=optional)
    if response["kind"] != "response":
        _fail("$.kind", "must equal 'response'")
    if type(response["version"]) is not int or response["version"] != PROTOCOL_VERSION:
        _fail("$.version", "must equal protocol version 1", "UNSUPPORTED_VERSION")
    _request_id(response["id"], "$.id")
    method = _method_name(response["method"], "$.method")
    if "context" in response:
        _validate_context(response["context"], "$.context")

    if response["ok"]:
        _object(response["result"], "$.result")
        if method in PROGRAM_METHODS and "context" not in response:
            _fail("$.context", f"is required on a successful {method} response")
        if method in CORE_METHODS - PROGRAM_METHODS and "context" in response:
            _fail("$.context", f"is forbidden for {method}")
        if method in CORE_METHODS:
            validate_method_payload(method, "response", response["result"])
    else:
        _validate_error(response["error"], "$.error")


def validate_event(value: Any) -> None:
    """Validate an event envelope and recognized core event data."""

    _check_json_shape(value)
    event = _object(value, "$")
    _exact_keys(
        event,
        "$",
        required={"kind", "version", "event", "sequence", "data"},
        optional={"context"},
    )
    if event["kind"] != "event":
        _fail("$.kind", "must equal 'event'")
    if type(event["version"]) is not int or event["version"] != PROTOCOL_VERSION:
        _fail("$.version", "must equal protocol version 1", "UNSUPPORTED_VERSION")
    name = _method_name(event["event"], "$.event")
    _integer(event["sequence"], "$.sequence", minimum=1, code="INVALID_REQUEST")
    _object(event["data"], "$.data")
    if "context" in event:
        _validate_context(event["context"], "$.context")
    if name == "analysis.progress" and "context" not in event:
        _fail("$.context", "is required for analysis.progress")
    if name in {"session.ready", "session.closing"} and "context" in event:
        _fail("$.context", f"is forbidden for {name}")
    if name in CORE_EVENTS:
        validate_event_data(name, event["data"])


def validate_wire_message(value: Any) -> None:
    """Dispatch validation by the explicit envelope kind."""

    if not isinstance(value, dict):
        _fail("$", "wire message must be an object")
    kind = value.get("kind")
    if kind == "request":
        validate_request(value)
    elif kind == "response":
        validate_response(value)
    elif kind == "event":
        validate_event(value)
    else:
        _fail("$.kind", "must be request, response, or event")


def validate_response_for_request(request: Any, response: Any) -> None:
    """Validate a response envelope and its correlation/context invariants."""

    validate_request(request)
    validate_response(response)
    if response["id"] != request["id"]:
        _fail("$.id", "does not match the request ID")
    if response["method"] != request["method"]:
        _fail("$.method", "does not match the request method")
    if response["ok"] and request["method"] in PROGRAM_METHODS:
        request_context = request["context"]
        response_context = response["context"]
        if response_context["programId"] != request_context["programId"]:
            _fail("$.context.programId", "does not match the request program", "PROGRAM_MISMATCH")
        if response_context["revision"] != request_context["revision"]:
            _fail("$.context.revision", "does not match the request revision", "REVISION_MISMATCH")


def validate_method_payload(method: str, direction: str, payload: Any) -> None:
    """Validate params (`request`) or a successful result (`response`) for a core method."""

    if method not in CORE_METHODS:
        _fail("$.method", f"unknown core method: {method}", "METHOD_NOT_FOUND")
    if direction not in {"request", "response"}:
        _fail("$.direction", "must be request or response", "INVALID_PARAMS")
    _object(payload, "$.payload", "INVALID_PARAMS")
    validator = _METHOD_VALIDATORS[(method, direction)]
    validator(payload, "$.payload")


def _validate_string_list(
    value: Any,
    path: str,
    *,
    maximum: int,
    item_maximum: int,
    pattern: re.Pattern[str] | None = None,
    unique: bool = False,
) -> list[str]:
    items = _array(value, path, maximum=maximum)
    normalized = [
        _string(item, f"{path}[{index}]", minimum=1, maximum=item_maximum, pattern=pattern)
        for index, item in enumerate(items)
    ]
    if unique and len(normalized) != len(set(normalized)):
        _fail(path, "items must be unique", "INVALID_PARAMS")
    return normalized


def _validate_hello_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"client", "protocol"},
        optional={"requestedCapabilities"},
        code="INVALID_PARAMS",
    )
    _validate_identity(payload["client"], f"{path}.client")
    _validate_protocol_range(payload["protocol"], f"{path}.protocol")
    if "requestedCapabilities" in payload:
        _validate_string_list(
            payload["requestedCapabilities"],
            f"{path}.requestedCapabilities",
            maximum=64,
            item_maximum=128,
            pattern=_METHOD_NAME,
            unique=True,
        )


def _validate_hello_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"selectedVersion", "sessionId", "server", "ghidra", "capabilities"},
        code="INVALID_PARAMS",
    )
    if type(payload["selectedVersion"]) is not int or payload["selectedVersion"] != PROTOCOL_VERSION:
        _fail(f"{path}.selectedVersion", "must equal 1", "INVALID_PARAMS")
    _string(payload["sessionId"], f"{path}.sessionId", minimum=1, maximum=128)
    _validate_identity(payload["server"], f"{path}.server")
    ghidra = _object(payload["ghidra"], f"{path}.ghidra", "INVALID_PARAMS")
    _exact_keys(ghidra, f"{path}.ghidra", required={"version"}, code="INVALID_PARAMS")
    _string(ghidra["version"], f"{path}.ghidra.version", minimum=1, maximum=128)
    _validate_capabilities(payload["capabilities"], f"{path}.capabilities")


def _validate_ping_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required=set(), optional={"nonce"}, code="INVALID_PARAMS")
    if "nonce" in payload:
        _string(payload["nonce"], f"{path}.nonce", minimum=1, maximum=128)


def _validate_ping_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"monotonicTimeMs", "state"},
        optional={"nonce"},
        code="INVALID_PARAMS",
    )
    if "nonce" in payload:
        _string(payload["nonce"], f"{path}.nonce", minimum=1, maximum=128)
    _integer(payload["monotonicTimeMs"], f"{path}.monotonicTimeMs", minimum=0)
    _enum(payload["state"], f"{path}.state", {"starting", "ready", "busy", "closing"})


def _validate_close_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required=set(), optional={"reason"}, code="INVALID_PARAMS")
    if "reason" in payload:
        _string(payload["reason"], f"{path}.reason", minimum=1, maximum=512)


def _validate_close_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required={"accepted"}, code="INVALID_PARAMS")
    if payload["accepted"] is not True:
        _fail(f"{path}.accepted", "must equal true", "INVALID_PARAMS")


def _validate_cancel_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required={"targetId"}, code="INVALID_PARAMS")
    _request_id(payload["targetId"], f"{path}.targetId", "INVALID_PARAMS")


def _validate_cancel_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required={"accepted", "state"}, code="INVALID_PARAMS")
    accepted = _boolean(payload["accepted"], f"{path}.accepted")
    state = _enum(
        payload["state"],
        f"{path}.state",
        {"requested", "already-complete", "not-cancellable"},
    )
    if accepted != (state == "requested"):
        _fail(path, "accepted must be true exactly when state is requested", "INVALID_PARAMS")


def _validate_summary_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(payload, path, required=set(), code="INVALID_PARAMS")


def _validate_summary_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    required = {
        "name",
        "executableFormat",
        "languageId",
        "compilerSpecId",
        "imageBase",
        "minAddress",
        "maxAddress",
        "sha256",
        "analysis",
        "memoryBlockCount",
        "instructionCount",
        "functionCount",
        "symbolCount",
    }
    _exact_keys(payload, path, required=required, code="INVALID_PARAMS")
    _string(payload["name"], f"{path}.name", minimum=1, maximum=512)
    _string(payload["executableFormat"], f"{path}.executableFormat", minimum=1, maximum=256)
    _string(payload["languageId"], f"{path}.languageId", minimum=1, maximum=256)
    _string(payload["compilerSpecId"], f"{path}.compilerSpecId", minimum=1, maximum=256)
    for field in ("imageBase", "minAddress", "maxAddress"):
        _address(payload[field], f"{path}.{field}")
    _string(payload["sha256"], f"{path}.sha256", minimum=64, maximum=64, pattern=_SHA256)
    analysis = _object(payload["analysis"], f"{path}.analysis", "INVALID_PARAMS")
    _exact_keys(
        analysis,
        f"{path}.analysis",
        required={"complete", "timedOut"},
        code="INVALID_PARAMS",
    )
    _boolean(analysis["complete"], f"{path}.analysis.complete")
    _boolean(analysis["timedOut"], f"{path}.analysis.timedOut")
    for field in ("memoryBlockCount", "instructionCount", "functionCount", "symbolCount"):
        _integer(payload[field], f"{path}.{field}", minimum=0)


def _validate_listing_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required=set(),
        optional={"start", "cursor", "direction", "limit", "includeBytes"},
        code="INVALID_PARAMS",
    )
    if ("start" in payload) == ("cursor" in payload):
        _fail(path, "exactly one of start or cursor is required", "INVALID_PARAMS")
    if "start" in payload:
        _address(payload["start"], f"{path}.start")
    if "cursor" in payload:
        _cursor(payload["cursor"], f"{path}.cursor")
    if "direction" in payload:
        _enum(payload["direction"], f"{path}.direction", {"forward", "backward"})
    if "limit" in payload:
        _integer(payload["limit"], f"{path}.limit", minimum=1, maximum=1_000, code="LIMIT_EXCEEDED")
    if "includeBytes" in payload:
        _boolean(payload["includeBytes"], f"{path}.includeBytes")


def _validate_listing_item(value: Any, path: str) -> None:
    item = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        item,
        path,
        required={"address", "length", "kind"},
        optional={"label", "mnemonic", "operands", "bytes", "flowType", "function"},
        code="INVALID_PARAMS",
    )
    _address(item["address"], f"{path}.address")
    length = _integer(item["length"], f"{path}.length", minimum=1, maximum=1_048_576)
    _enum(item["kind"], f"{path}.kind", {"instruction", "data", "undefined"})
    bounds = {
        "label": 1_024,
        "mnemonic": 256,
        "operands": 4_096,
        "flowType": 128,
        "function": 1_024,
    }
    for field, maximum in bounds.items():
        if field in item:
            _string(item[field], f"{path}.{field}", minimum=1 if field != "operands" else 0, maximum=maximum)
    if "bytes" in item:
        encoded = _string(item["bytes"], f"{path}.bytes", maximum=2_097_152, pattern=_HEX_BYTES)
        if len(encoded) != length * 2:
            _fail(f"{path}.bytes", "must contain exactly length bytes", "INVALID_PARAMS")


def _validate_listing_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"items", "truncated"},
        optional={"nextCursor", "previousCursor"},
        code="INVALID_PARAMS",
    )
    items = _array(payload["items"], f"{path}.items", maximum=1_000)
    for index, item in enumerate(items):
        _validate_listing_item(item, f"{path}.items[{index}]")
    _boolean(payload["truncated"], f"{path}.truncated")
    for field in ("nextCursor", "previousCursor"):
        if field in payload:
            _cursor(payload[field], f"{path}.{field}")


_SYMBOL_KINDS = frozenset(
    {"label", "function", "namespace", "class", "parameter", "local", "external", "library", "unknown"}
)


def _validate_symbols_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"query"},
        optional={"kinds", "limit", "cursor"},
        code="INVALID_PARAMS",
    )
    _string(payload["query"], f"{path}.query", maximum=256)
    if "kinds" in payload:
        kinds = _array(payload["kinds"], f"{path}.kinds", maximum=10)
        normalized = [
            _enum(item, f"{path}.kinds[{index}]", _SYMBOL_KINDS)
            for index, item in enumerate(kinds)
        ]
        if len(normalized) != len(set(normalized)):
            _fail(f"{path}.kinds", "items must be unique", "INVALID_PARAMS")
    if "limit" in payload:
        _integer(payload["limit"], f"{path}.limit", minimum=1, maximum=500, code="LIMIT_EXCEEDED")
    if "cursor" in payload:
        _cursor(payload["cursor"], f"{path}.cursor")


def _validate_symbol_item(value: Any, path: str) -> None:
    item = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        item,
        path,
        required={"name", "address", "kind", "primary"},
        optional={"namespace", "source"},
        code="INVALID_PARAMS",
    )
    _string(item["name"], f"{path}.name", minimum=1, maximum=1_024)
    _address(item["address"], f"{path}.address")
    _enum(item["kind"], f"{path}.kind", _SYMBOL_KINDS)
    _boolean(item["primary"], f"{path}.primary")
    if "namespace" in item:
        _string(item["namespace"], f"{path}.namespace", minimum=1, maximum=2_048)
    if "source" in item:
        _string(item["source"], f"{path}.source", minimum=1, maximum=128)


def _validate_symbols_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"items", "truncated"},
        optional={"nextCursor"},
        code="INVALID_PARAMS",
    )
    items = _array(payload["items"], f"{path}.items", maximum=500)
    for index, item in enumerate(items):
        _validate_symbol_item(item, f"{path}.items[{index}]")
    _boolean(payload["truncated"], f"{path}.truncated")
    if "nextCursor" in payload:
        _cursor(payload["nextCursor"], f"{path}.nextCursor")


def _validate_decompile_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"address"},
        optional={"maxChars"},
        code="INVALID_PARAMS",
    )
    _address(payload["address"], f"{path}.address")
    if "maxChars" in payload:
        _integer(payload["maxChars"], f"{path}.maxChars", minimum=1_024, maximum=500_000, code="LIMIT_EXCEEDED")


def _validate_decompile_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"function", "language", "text", "truncated", "warnings"},
        code="INVALID_PARAMS",
    )
    function = _object(payload["function"], f"{path}.function", "INVALID_PARAMS")
    _exact_keys(
        function,
        f"{path}.function",
        required={"name", "entryPoint", "signature"},
        code="INVALID_PARAMS",
    )
    _string(function["name"], f"{path}.function.name", minimum=1, maximum=1_024)
    _address(function["entryPoint"], f"{path}.function.entryPoint")
    _string(function["signature"], f"{path}.function.signature", minimum=1, maximum=16_384)
    if payload["language"] != "c":
        _fail(f"{path}.language", "must equal 'c'", "INVALID_PARAMS")
    _string(payload["text"], f"{path}.text", maximum=500_000)
    _boolean(payload["truncated"], f"{path}.truncated")
    _validate_string_list(payload["warnings"], f"{path}.warnings", maximum=64, item_maximum=2_048)


def _validate_references_params(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"address"},
        optional={"direction", "limit", "cursor"},
        code="INVALID_PARAMS",
    )
    _address(payload["address"], f"{path}.address")
    if "direction" in payload:
        _enum(payload["direction"], f"{path}.direction", {"to", "from", "both"})
    if "limit" in payload:
        _integer(payload["limit"], f"{path}.limit", minimum=1, maximum=2_000, code="LIMIT_EXCEEDED")
    if "cursor" in payload:
        _cursor(payload["cursor"], f"{path}.cursor")


def _validate_reference_item(value: Any, path: str) -> None:
    item = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        item,
        path,
        required={"fromAddress", "toAddress", "type", "primary", "external"},
        optional={"operandIndex"},
        code="INVALID_PARAMS",
    )
    _address(item["fromAddress"], f"{path}.fromAddress")
    _address(item["toAddress"], f"{path}.toAddress")
    _string(item["type"], f"{path}.type", minimum=1, maximum=128)
    _boolean(item["primary"], f"{path}.primary")
    _boolean(item["external"], f"{path}.external")
    if "operandIndex" in item:
        _integer(item["operandIndex"], f"{path}.operandIndex", minimum=-1, maximum=65_535)


def _validate_references_result(value: Any, path: str) -> None:
    payload = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        payload,
        path,
        required={"items", "truncated"},
        optional={"nextCursor"},
        code="INVALID_PARAMS",
    )
    items = _array(payload["items"], f"{path}.items", maximum=2_000)
    for index, item in enumerate(items):
        _validate_reference_item(item, f"{path}.items[{index}]")
    _boolean(payload["truncated"], f"{path}.truncated")
    if "nextCursor" in payload:
        _cursor(payload["nextCursor"], f"{path}.nextCursor")


_METHOD_VALIDATORS: dict[tuple[str, str], Callable[[Any, str], None]] = {
    ("session.hello", "request"): _validate_hello_params,
    ("session.hello", "response"): _validate_hello_result,
    ("session.ping", "request"): _validate_ping_params,
    ("session.ping", "response"): _validate_ping_result,
    ("session.close", "request"): _validate_close_params,
    ("session.close", "response"): _validate_close_result,
    ("request.cancel", "request"): _validate_cancel_params,
    ("request.cancel", "response"): _validate_cancel_result,
    ("program.summary", "request"): _validate_summary_params,
    ("program.summary", "response"): _validate_summary_result,
    ("listing.window", "request"): _validate_listing_params,
    ("listing.window", "response"): _validate_listing_result,
    ("symbols.search", "request"): _validate_symbols_params,
    ("symbols.search", "response"): _validate_symbols_result,
    ("decompile.function", "request"): _validate_decompile_params,
    ("decompile.function", "response"): _validate_decompile_result,
    ("references.list", "request"): _validate_references_params,
    ("references.list", "response"): _validate_references_result,
}


def validate_event_data(event: str, data: Any) -> None:
    """Validate the data object for a recognized core event."""

    if event not in CORE_EVENTS:
        _fail("$.event", f"unknown core event: {event}", "METHOD_NOT_FOUND")
    _EVENT_VALIDATORS[event](data, "$.data")


def _validate_ready_event(value: Any, path: str) -> None:
    data = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        data,
        path,
        required={"sessionId", "protocol", "server", "ghidra", "context", "programSha256"},
        code="INVALID_PARAMS",
    )
    _string(data["sessionId"], f"{path}.sessionId", minimum=1, maximum=128)
    _validate_protocol_range(data["protocol"], f"{path}.protocol")
    _validate_identity(data["server"], f"{path}.server")
    ghidra = _object(data["ghidra"], f"{path}.ghidra", "INVALID_PARAMS")
    _exact_keys(ghidra, f"{path}.ghidra", required={"version"}, code="INVALID_PARAMS")
    _string(ghidra["version"], f"{path}.ghidra.version", minimum=1, maximum=128)
    _validate_context(data["context"], f"{path}.context")
    _string(
        data["programSha256"],
        f"{path}.programSha256",
        minimum=64,
        maximum=64,
        pattern=_SHA256,
    )


def _validate_progress_event(value: Any, path: str) -> None:
    data = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        data,
        path,
        required={"phase", "completed", "total"},
        optional={"message"},
        code="INVALID_PARAMS",
    )
    _enum(data["phase"], f"{path}.phase", {"import", "analysis", "finalizing"})
    completed = _integer(data["completed"], f"{path}.completed", minimum=0)
    total = _integer(data["total"], f"{path}.total", minimum=0)
    if total and completed > total:
        _fail(path, "completed must not exceed total", "INVALID_PARAMS")
    if "message" in data:
        _string(data["message"], f"{path}.message", minimum=1, maximum=512)


def _validate_warning_event(value: Any, path: str) -> None:
    data = _object(value, path, "INVALID_PARAMS")
    _exact_keys(data, path, required={"code", "message"}, code="INVALID_PARAMS")
    _string(data["code"], f"{path}.code", minimum=1, maximum=128, pattern=_WARNING_CODE)
    _string(data["message"], f"{path}.message", minimum=1, maximum=2_048)


def _validate_closing_event(value: Any, path: str) -> None:
    data = _object(value, path, "INVALID_PARAMS")
    _exact_keys(
        data,
        path,
        required={"reason"},
        optional={"message"},
        code="INVALID_PARAMS",
    )
    _enum(
        data["reason"],
        f"{path}.reason",
        {"requested", "parent-lost", "idle-timeout", "engine-exit", "protocol-error"},
    )
    if "message" in data:
        _string(data["message"], f"{path}.message", minimum=1, maximum=512)


_EVENT_VALIDATORS: dict[str, Callable[[Any, str], None]] = {
    "session.ready": _validate_ready_event,
    "analysis.progress": _validate_progress_event,
    "engine.warning": _validate_warning_event,
    "session.closing": _validate_closing_event,
}


def frame_prefix(session_nonce: str) -> bytes:
    """Build the exact v1 frame prefix for one supervised worker session."""

    nonce = _session_nonce(session_nonce)
    return FRAME_PREFIX + nonce.encode("ascii") + b" "


def encode_frame(
    message: Mapping[str, Any],
    session_nonce: str,
    *,
    max_frame_bytes: int = MAX_FRAME_BYTES,
) -> bytes:
    """Validate and encode one compact, deterministic stdio frame."""

    prefix = frame_prefix(session_nonce)
    _integer(max_frame_bytes, "max_frame_bytes", minimum=len(prefix) + 3, maximum=MAX_FRAME_BYTES)
    validate_wire_message(message)
    try:
        body = json.dumps(
            message,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        _fail("$", f"cannot encode JSON: {error}")
    frame = prefix + body + b"\n"
    if len(frame) > max_frame_bytes:
        _fail("$", f"frame exceeds {max_frame_bytes} bytes", "LIMIT_EXCEEDED")
    return frame


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail("$", f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> NoReturn:
    _fail("$", f"non-finite JSON number is forbidden: {value}")


def decode_frame(
    frame: bytes,
    session_nonce: str,
    *,
    max_frame_bytes: int = MAX_FRAME_BYTES,
) -> dict[str, Any]:
    """Decode and validate exactly one prefixed LF-terminated stdio frame."""

    expected_prefix = frame_prefix(session_nonce)
    _integer(
        max_frame_bytes,
        "max_frame_bytes",
        minimum=len(expected_prefix) + 3,
        maximum=MAX_FRAME_BYTES,
    )
    if not isinstance(frame, bytes):
        _fail("$", "frame must be bytes")
    if len(frame) > max_frame_bytes:
        _fail("$", f"frame exceeds {max_frame_bytes} bytes", "LIMIT_EXCEEDED")
    if not frame.startswith(FRAME_PREFIX):
        _fail("$", "frame version prefix is missing")
    nonce_start = len(FRAME_PREFIX)
    nonce_end = nonce_start + 32
    if len(frame) <= nonce_end or frame[nonce_end : nonce_end + 1] != b" ":
        _fail("$.sessionNonce", "frame nonce or separating space is missing")
    try:
        actual_nonce = frame[nonce_start:nonce_end].decode("ascii", "strict")
    except UnicodeDecodeError:
        _fail("$.sessionNonce", "frame nonce must be lowercase ASCII hexadecimal")
    _session_nonce(actual_nonce, "$.sessionNonce")
    if not hmac.compare_digest(actual_nonce, session_nonce):
        _fail("$.sessionNonce", "frame nonce does not match the supervised session")
    if not frame.endswith(b"\n") or frame.endswith(b"\r\n"):
        _fail("$", "frame must end with one LF")
    body = frame[len(expected_prefix) : -1]
    if b"\n" in body or b"\r" in body:
        _fail("$", "raw line breaks are forbidden inside a frame")
    try:
        text = body.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        _fail("$", f"frame is not valid UTF-8: {error}")
    try:
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite,
        )
    except ContractViolation:
        raise
    except (json.JSONDecodeError, ValueError) as error:
        _fail("$", f"frame contains invalid JSON: {error}")
    validate_wire_message(value)
    return value


def generate_gateway_token(entropy_bytes: int = 32) -> str:
    """Return a URL-safe bearer token with at least 256 bits of OS entropy."""

    if type(entropy_bytes) is not int or not 32 <= entropy_bytes <= 128:
        raise ValueError("entropy_bytes must be between 32 and 128")
    return secrets.token_urlsafe(entropy_bytes)


def generate_session_nonce() -> str:
    """Return a fresh 128-bit lowercase hexadecimal stdio frame nonce."""

    return secrets.token_hex(16)


def authorization_header(token: str) -> str:
    """Build an Authorization value after enforcing the v1 token shape."""

    _string(token, "token", minimum=43, maximum=128, pattern=_TOKEN, code="AUTH_FAILED")
    return f"Bearer {token}"


def authenticate_bearer_header(header: str | None, expected_token: str) -> bool:
    """Check a gateway Authorization value without leaking token-prefix timing."""

    if not isinstance(header, str) or not isinstance(expected_token, str):
        return False
    if len(expected_token) < 43 or len(expected_token) > 128 or _TOKEN.fullmatch(expected_token) is None:
        return False
    prefix = "Bearer "
    if not header.startswith(prefix):
        return False
    candidate = header[len(prefix) :]
    if len(candidate) < 43 or len(candidate) > 128 or _TOKEN.fullmatch(candidate) is None:
        return False
    return hmac.compare_digest(candidate.encode("ascii"), expected_token.encode("ascii"))


def validate_gateway_descriptor(value: Any) -> None:
    """Validate an ephemeral loopback gateway handoff descriptor."""

    _check_json_shape(value)
    descriptor = _object(value, "$")
    _exact_keys(
        descriptor,
        "$",
        required={"baseUrl", "token", "protocolVersion", "pid"},
    )
    base_url = _string(
        descriptor["baseUrl"],
        "$.baseUrl",
        minimum=1,
        maximum=64,
        code="INVALID_REQUEST",
    )
    try:
        parsed = urlsplit(base_url)
        port = parsed.port
    except ValueError as error:
        _fail("$.baseUrl", f"has an invalid port: {error}")
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"127.0.0.1", "::1"}
        or port is None
        or not 1 <= port <= 65_535
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
    ):
        _fail("$.baseUrl", "must be an origin-only HTTP URL on 127.0.0.1 or [::1]")
    _string(
        descriptor["token"],
        "$.token",
        minimum=43,
        maximum=128,
        pattern=_TOKEN,
        code="AUTH_FAILED",
    )
    if type(descriptor["protocolVersion"]) is not int or descriptor["protocolVersion"] != 1:
        _fail("$.protocolVersion", "must equal 1", "UNSUPPORTED_VERSION")
    _integer(descriptor["pid"], "$.pid", minimum=1, code="INVALID_REQUEST")


__all__ = [
    "CORE_DEFAULTS",
    "CORE_EVENTS",
    "CORE_LIMITS",
    "CORE_METHODS",
    "ContractViolation",
    "ERROR_CODES",
    "FRAME_PREFIX",
    "MAX_FRAME_BYTES",
    "MAX_TIMEOUT_MS",
    "PROGRAM_METHODS",
    "PROTOCOL_VERSION",
    "authenticate_bearer_header",
    "authorization_header",
    "decode_frame",
    "encode_frame",
    "frame_prefix",
    "generate_gateway_token",
    "generate_session_nonce",
    "validate_event",
    "validate_event_data",
    "validate_gateway_descriptor",
    "validate_method_payload",
    "validate_request",
    "validate_response",
    "validate_response_for_request",
    "validate_wire_message",
]
