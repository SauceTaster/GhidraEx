# GhidraEx engine protocol v1

The key words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** describe requirements for interoperable
implementations. JSON examples are illustrative; the schemas in `schemas/` are machine-readable.

## 1. Session model

A v1 engine session owns exactly one program and exposes revision `0`. Import and initial analysis
happen before the engine becomes ready. Program selection and binary paths are launch-time
supervisor inputs, never protocol request parameters.

The engine emits `session.ready`, then accepts `session.hello`. Except for `session.hello`, an engine
MUST reject requests before successful negotiation with `SESSION_NOT_READY`. A client MUST stop
issuing new work after `session.close` is accepted or `session.closing` is observed.

Requests may execute concurrently. Responses can arrive out of order and are correlated solely by
`id`. Events are ordered by their monotonically increasing `sequence` within one session. Missing
event sequence numbers mean that the client lost state and SHOULD refresh affected views.

## 2. Envelopes

All wire documents are UTF-8 JSON objects. Request IDs are caller-generated, unique among in-flight
requests, and no longer than 128 characters. The response echoes both `id` and `method`.

Request:

```json
{
  "kind": "request",
  "version": 1,
  "id": "listing-17",
  "method": "listing.window",
  "context": {"programId": "p-4cc8", "revision": 0},
  "timeoutMs": 5000,
  "params": {"start": "00401000", "direction": "forward", "limit": 128}
}
```

Successful response:

```json
{
  "kind": "response",
  "version": 1,
  "id": "listing-17",
  "method": "listing.window",
  "ok": true,
  "context": {"programId": "p-4cc8", "revision": 0},
  "result": {"items": [], "truncated": false}
}
```

Error response:

```json
{
  "kind": "response",
  "version": 1,
  "id": "listing-17",
  "method": "listing.window",
  "ok": false,
  "error": {
    "code": "REVISION_MISMATCH",
    "message": "requested revision is no longer current",
    "retryable": true,
    "details": {"currentRevision": 1}
  }
}
```

Event:

```json
{
  "kind": "event",
  "version": 1,
  "event": "analysis.progress",
  "sequence": 2,
  "context": {"programId": "p-4cc8", "revision": 0},
  "data": {"phase": "analysis", "completed": 43, "total": 100}
}
```

Successful program-reading responses MUST echo the context used to compute the result. A client MUST
discard a result whose context no longer matches its active view. Addresses are canonical strings
returned by the engine and MUST be treated as opaque; clients MUST NOT parse them as JavaScript
numbers. Cursors are opaque, session-local, query-bound, and invalid after session restart.

## 3. Negotiation and capabilities

`session.ready` data contains `sessionId`, a supported protocol range, engine and Ghidra versions,
the program context, and `programSha256` as exactly 64 lowercase hexadecimal characters. Before
forwarding readiness or sending `session.hello`, the supervisor MUST compare that digest with the
SHA-256 it computed directly from the launch binary. A mismatch is a failed worker launch: the
supervisor terminates the worker and MUST NOT expose the session. The client selects an overlapping
version with `session.hello`:

```json
{
  "kind": "request",
  "version": 1,
  "id": "hello-1",
  "method": "session.hello",
  "params": {
    "client": {"name": "ghidraex-vscode", "version": "0.1.0"},
    "protocol": {"min": 1, "max": 1},
    "requestedCapabilities": ["decompile.function", "references.list"]
  }
}
```

The successful result includes `selectedVersion`, identity metadata, and capabilities:

```json
{
  "selectedVersion": 1,
  "sessionId": "s-14d2",
  "server": {"name": "ghidraex-engine", "version": "0.1.0"},
  "ghidra": {"version": "12.1.2"},
  "capabilities": {
    "methods": ["session.ping", "program.summary", "listing.window"],
    "events": ["analysis.progress", "engine.warning", "session.closing"],
    "transports": ["stdio", "loopback-http"],
    "limits": {
      "maxFrameBytes": 2097152,
      "maxTimeoutMs": 30000,
      "maxListingItems": 1000,
      "maxSymbolItems": 500,
      "maxReferenceItems": 2000,
      "maxDecompileChars": 500000
    }
  }
}
```

The result MUST advertise only usable methods. A requested capability is a preference, not a demand;
absence means the client hides or disables that feature. No client may infer debugger, scripting,
write, or plugin authority from Ghidra's installation alone.

## 4. Core methods

The exact parameter and result shapes are in `schemas/core-methods.schema.json`.

| Method | Context | Purpose | Primary bound |
| --- | --- | --- | --- |
| `session.hello` | forbidden | negotiate version and capabilities | 64 requested capabilities |
| `session.ping` | forbidden | liveness and engine state | 128-character nonce |
| `session.close` | forbidden | graceful, idempotent shutdown | 512-character reason |
| `request.cancel` | forbidden | best-effort cancellation by request ID | one in-flight request |
| `program.summary` | required | immutable program, analysis metadata, and listing extent | fixed-size object |
| `listing.window` | required | page instructions/data around an address or cursor | 1,000 items |
| `symbols.search` | required | deterministic symbol search and paging | 500 items |
| `decompile.function` | required | decompile the containing function | 500,000 UTF-8 characters |
| `references.list` | required | page references to/from an address | 2,000 items |

Every method also obeys the request `timeoutMs` range of 100 through 30,000 ms. The server MAY apply
a smaller advertised default. When omitted, v1 uses these deterministic defaults: `timeoutMs` is
5,000; listing direction is `forward`, listing limit is 128, and bytes are excluded; symbol limit
is 100 with all kinds included; decompile text is capped at 200,000 characters; reference direction
is `both` and reference limit is 256. Limit violations fail with `LIMIT_EXCEEDED`; the server MUST NOT
silently exceed a requested or advertised maximum. Page ordering MUST be deterministic for an
unchanged program revision.

`request.cancel` is a race-safe acknowledgement, not proof that execution stopped. A result state
of `requested` means cancellation was delivered. The target subsequently returns
`REQUEST_CANCELLED`, returns its normal response if it won the race, or the engine is terminated by
the supervisor if it cannot honor the deadline. Repeating cancellation is safe.

## 5. Error taxonomy

| Code | Meaning | Normally retryable |
| --- | --- | --- |
| `INVALID_REQUEST` | malformed envelope, duplicate ID, or invalid JSON value | no |
| `UNSUPPORTED_VERSION` | no compatible protocol major | no |
| `AUTH_FAILED` | gateway credentials rejected | no |
| `METHOD_NOT_FOUND` | method is unknown or not advertised | no |
| `INVALID_PARAMS` | method payload fails its contract | no |
| `SESSION_NOT_READY` | negotiation or initial analysis is incomplete | yes |
| `PROGRAM_MISMATCH` | request names another session's program | yes |
| `REVISION_MISMATCH` | requested revision is stale or unknown | yes |
| `NOT_FOUND` | requested address, function, or entity is absent | no |
| `LIMIT_EXCEEDED` | request or generated result crosses a hard bound | no |
| `DEADLINE_EXCEEDED` | operation missed its deadline | yes |
| `REQUEST_CANCELLED` | operation was cooperatively cancelled | yes |
| `ENGINE_BUSY` | bounded worker capacity is temporarily exhausted | yes |
| `ENGINE_UNAVAILABLE` | child process or required service is unavailable | yes |
| `GHIDRA_FAILURE` | Ghidra reported a domain operation failure | depends on details |
| `INTERNAL` | unexpected engine defect; details MUST NOT leak secrets | no |

`message` is for people and MUST NOT be parsed. Clients branch on `code` and `retryable`. Details are
bounded structured diagnostics and MUST NOT include local filesystem paths, bearer tokens, full
stack traces, or binary contents. A retryable error MAY provide `retryAfterMs`.

## 6. Framed stdio transport

Before spawning a worker, the supervisor generates 16 bytes with a cryptographically secure random
source and encodes them as exactly 32 lowercase hexadecimal characters. That session nonce is
passed to the worker through the supervised launch channel and remains fixed for the worker's
lifetime. It is a frame discriminator, not gateway authentication and not the `session.ping` nonce.

Each protocol frame is one compact JSON document prefixed with the ASCII bytes `GHIDRAEX/1 `, the
exact session nonce, and one ASCII space, then terminated by LF:

```text
GHIDRAEX/1 0123456789abcdef0123456789abcdef {"kind":"request",...}\n
```

The complete nonce-bearing prefix is required in both directions. Decoders MUST receive the
expected nonce out of band, validate its exact shape, and accept only an exact match. A frame MUST
NOT exceed 2,097,152 bytes including version prefix, nonce, spaces, JSON, and LF. Raw line feeds
inside JSON strings are invalid JSON and therefore invalid frames. Engines MUST emit one frame
atomically under a write lock.

The supervisor recognizes only lines beginning with the complete prefix for its expected nonce.
Other child stdout—including version-prefix lookalikes bearing malformed, previous, or unrelated
nonces—is captured as Ghidra launcher output in a bounded diagnostic ring and never delivered as a
protocol message. Once a line matches the complete expected prefix, invalid framing or JSON is a
protocol failure. The engine never interprets stdin without its complete expected prefix as a
request. Diagnostics SHOULD go to stderr. EOF, a broken pipe, or parent death closes the session.
Implementations MUST reject duplicate JSON object keys, non-finite numbers, excessive nesting, and
invalid UTF-8.

## 7. Token-authenticated loopback gateway

The gateway exposes the same envelopes, without the stdio prefix:

- `POST /v1/requests` accepts one request JSON document and returns its response JSON document.
- `GET /v1/events?after=<sequence>` returns bounded `application/x-ndjson`; each LF-terminated line
  is one event envelope. Browser-derived clients use streaming `fetch` so they can set an
  authorization header and inspect sequence-gap headers.
- `GET /v1/health` returns bounded process health and no program contents.

Every endpoint, including health, MUST require `Authorization: Bearer <token>`. The token is at least
32 cryptographically random bytes, is handed to the launching host out of band, never appears in a
URL, log, workspace file, command-line argument, or error. Comparison is constant-time. The gateway
MUST bind only an OS-confirmed loopback address on an ephemeral port, disable wildcard CORS, reject
unapproved `Origin` values, set `Cache-Control: no-store`, reject redirects, and cap headers and
bodies. It MUST NOT follow proxy environment variables for loopback requests.

The host verifies the startup descriptor's child PID and loopback URL before using the token. A
gateway lease is tied to its parent/session; parent death or explicit close stops the listener and
engine. An implementation MAY add bounded idle expiry; when configured, expiry MUST close both the
listener and engine. TLS is not required for a correctly constrained loopback connection, but token
authentication remains mandatory because other local processes and browser pages are not trusted.

HTTP status conveys transport handling only. A syntactically valid request returns a protocol
response even when `ok` is false. Authentication failures SHOULD return HTTP 401 with no protocol
details; oversize bodies return 413; unsupported content types return 415; saturation may return
429. Clients MUST NOT synthesize domain success from an HTTP status.

## 8. Backpressure, deadlines, and recovery

Servers advertise bounded worker capacity and MUST reject excess concurrent work with `ENGINE_BUSY`
instead of creating unbounded queues. The supervisor sets a harder wall-clock deadline beyond the
engine's cooperative deadline. When an operation wedges inside Ghidra, the supervisor terminates the
entire process group, reports `ENGINE_UNAVAILABLE` to pending callers, deletes the temporary project,
and starts a fresh session only after explicit host policy permits it.

Clients can retry only idempotent read methods in v1, using a new request ID and the same context.
They MUST renegotiate and refresh program state after any session restart; request IDs, cursors, and
event sequence numbers never cross sessions.

## 9. Extensibility and compatibility

Core v1 schemas are strict. Unknown top-level fields or unknown core payload fields are invalid.
Experimental methods use `x.<vendor>.<name>` and MUST be capability-advertised. A client may preserve
but must not execute an unknown capability. New core methods may be introduced without changing
existing v1 payloads; breaking envelope, field, or semantic changes require v2 (`GHIDRAEX/2 ` and
`/v2/`). A multi-version gateway keeps versions on separate paths and never silently translates a
request across incompatible majors.
