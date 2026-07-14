# ADR-0001: Process-isolated, versioned Ghidra engine

- Status: accepted
- Date: 2026-07-13
- Decision owners: GhidraEx maintainers

## Context

GhidraEx has multiple UI hosts with unrelated runtime constraints: JavaFX, a browser-capable web
client, IntelliJ Platform, and VS Code. Loading Ghidra classes directly into each host would couple
every release to Ghidra's module graph, supported JDK, global state, and plugin class loaders. A
Ghidra crash or runaway analysis would also take down the UI. Reimplementing analysis in each host
would create divergent behavior and make cross-host testing impractical.

Ghidra's program model is rich and stateful. It must not leak across this boundary as serialized
Java objects, reflective handles, or UI-specific models. Requests also need hard resource limits;
reverse-engineering inputs are untrusted and a single unbounded listing, reference, or decompiler
request can exhaust a desktop process.

## Decision

Run Ghidra in a supervised child process and expose a versioned, JSON-only engine protocol.

One engine session owns one imported program and one immutable program revision in v1. The
supervisor owns the temporary Ghidra project, child JDK selection, process group, timeouts, logs,
and cleanup. Hosts own presentation state. Neither side shares a JVM, class loader, database
handle, or Ghidra object identity.

The same request, response, and event envelopes run over two transports:

1. Nonce-scoped framed stdio between a supervisor and the engine child.
2. A loopback-only HTTP gateway, protected by a per-launch 256-bit bearer token, for native hosts
   that cannot or should not supervise Ghidra directly.

The semantic contract is transport-neutral. A gateway translates transport details only; it does
not invent different methods or payloads.

Protocol v1 is intentionally read-only and bounded. Clients negotiate from advertised
capabilities instead of assuming that every engine implements every future feature. Addresses,
cursors, program IDs, and request IDs are opaque strings at the UI boundary. Every program read
is tied to `{programId, revision}` so stale results cannot silently populate a newer view.

## Consequences

### Positive

- Ghidra's JDK and module requirements do not constrain UI runtimes.
- A hung or crashed engine is restartable without corrupting host state.
- All four hosts can share fixtures, recordings, conformance tests, and failure injection.
- Wire DTOs can evolve independently of Ghidra's internal APIs.
- Strict limits and cancellation create predictable backpressure.
- The loopback gateway supports both desktop and browser-derived hosts without embedding a web
  view as the product architecture.

### Costs

- DTO conversion and JSON framing add latency and implementation work.
- Large results must be paged; no API may return a live Ghidra iterator.
- Cancellation is cooperative inside a Ghidra operation and may end in process termination when a
  deadline cannot be honored.
- Debugging, scripting, mutations, and plugins need explicit security and transaction designs;
  they cannot be exposed as arbitrary Java execution.
- A gateway adds a local attack surface and therefore requires strict bind, token, origin, and
  lifecycle controls.

## Rejected alternatives

### Embed Ghidra in every host JVM

This provides low call overhead but makes class-loader, JDK, native-library, global-state, and
crash isolation problems host-specific. It is not available to a browser host and would make VS
Code depend on a second in-process runtime.

### Use Ghidra's domain objects as the API

Java serialization, RMI, reflection, and generic object handles preserve internal coupling and
create unsafe lifetime semantics. They also prevent useful schema validation and non-Java clients.

### Make the gateway the canonical API

HTTP is useful at the host boundary, but the engine must remain directly testable and superviseable
without opening a port. Framed stdio is the canonical process boundary; the gateway is an
equivalent adapter.

### Start with arbitrary scripting or plugin loading

Those features expand authority from reading an imported binary to filesystem, process, and code
execution. They require a separate capability, consent, sandbox, provenance, and audit design.

## Non-negotiable invariants

- The engine never trusts request-provided filesystem paths in v1.
- Every collection and textual result has a documented hard maximum.
- A response echoes both request ID and method.
- Successful program reads echo the exact program context used.
- Protocol output is distinguishable from Ghidra log output.
- The supervisor supplies a fresh 128-bit stdio frame nonce for every worker and accepts frames
  bearing exactly that nonce; a previous or parallel session cannot inject a frame.
- `session.ready` binds the worker to the launch input with its lowercase SHA-256, which the
  supervisor verifies against its own pre-launch digest before negotiation.
- Unknown methods fail explicitly; clients use negotiated capabilities.
- Authentication tokens are never accepted in URLs or persisted in workspace configuration.
- A lost parent, closed stdio stream, expired lease, or gateway shutdown terminates the session and
  cleans up its project.
- New protocol majors coexist at separate framing prefixes and HTTP paths during migration.

## Evolution

Backward-compatible additions may add optional fields, capabilities, events, and methods only when
v1 readers are already required to tolerate them at the relevant extension point. Existing v1
envelopes and core method payloads are otherwise strict. Breaking field or semantic changes require
a new protocol major. Long-lived experimental methods use an `x.<vendor>.<name>` namespace and may
not be advertised as core.
