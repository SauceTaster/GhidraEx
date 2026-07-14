# Real Ghidra engine integration

This directory contains both real, non-synthetic Ghidra paths:

- [`ghidra_bridge.py`](ghidra_bridge.py) runs a one-shot import/analyze/export job through
  [`ExportProgramFacts.java`](scripts/ExportProgramFacts.java). It produces a deterministic bounded
  snapshot for compatibility and regression testing.
- [`session.py`](session.py) supervises a persistent `analyzeHeadless` process whose post-script is
  [`GhidraExServer.java`](scripts/GhidraExServer.java). The process keeps one analyzed program and a
  decompiler alive while it serves the versioned engine protocol.

Both paths are intentionally process-isolated. Ghidra 12.1.x can run on its supported JDK 21 while
the JavaFX and IntelliJ experiments remain on JDK 25. No Ghidra `Program`, database handle, Swing
component, native decompiler handle, or classloader identity crosses the boundary. Clients consume
bounded immutable JSON described by the [engine protocol](../protocol/PROTOCOL.md).

## Install

The real integration test has been validated against the official Ghidra 12.1.2 release with
Temurin 21.0.11+10 and Python 3.10+. Other current Ghidra 12.x releases should work through the
same public scripting API, but the integration test remains the compatibility gate.

Download and extract an official Ghidra release, then point the bridge at it:

```bash
export GHIDRA_HOME="/path/to/ghidra_12.1.2_PUBLIC"
export GHIDRA_JAVA_HOME="/path/to/jdk-21"
```

`GHIDRA_JAVA_HOME` is optional when Ghidra can already find a supported JDK. Unlike `JAVA_HOME`, it
is applied only to the Ghidra child process. Child-JDK discovery prefers `GHIDRA_JAVA_HOME`, then a
repository-local JDK 21, then inherited `JAVA_HOME`. Ghidra discovery checks, in order:

1. `--ghidra-home`;
2. `GHIDRA_HOME` or `GHIDRA_INSTALL_DIR`;
3. ignored repository-local `.toolchains/ghidra_*_PUBLIC` installs;
4. common user, `/opt`, `/usr/local`, and macOS application locations; and
5. `analyzeHeadless` on `PATH`.

Confirm the resolved sidecar and JDK:

```bash
python3 integration/ghidra/ghidra_bridge.py doctor
```

## Batch snapshot

```bash
python3 integration/ghidra/ghidra_bridge.py analyze /path/to/binary \
  --output /tmp/program-facts.json
```

By default the bridge creates an ephemeral Ghidra project, auto-analyzes the import, validates that
Ghidra's executable SHA-256 matches the requested input, validates schema and collection limits,
normalizes the JSON, and deletes the project. Useful controls include:

- `--max-functions`, `--max-symbols`, and `--max-instructions` for bounded UI projections;
- `--analysis-timeout` for Ghidra's per-file analyzer timeout;
- `--process-timeout` for the entire child process;
- `--max-cpu` to keep concurrent sidecars from exhausting a workstation; and
- `--project-directory ... --keep-project` when retaining a project for investigation
  (`--keep-project` is rejected without an explicit directory).

The command writes only the JSON document to standard output. Ghidra's verbose process output is
captured and included in errors only, so the interface can be consumed directly by another process.

## Persistent session

`GhidraEngineSession.start()` computes the binary SHA-256, creates an ephemeral project, starts a new
child process group, imports and analyzes the binary, waits for `session.ready`, verifies the
worker-reported digest and immutable revision, then negotiates protocol v1. Every stdio frame carries
a fresh supervisor-generated 128-bit nonce so launcher output and another session's frames cannot be
mistaken for protocol messages.

Use the session directly from Python when building or testing another trusted local adapter:

```python
from integration.ghidra.ghidra_bridge import GhidraInstallation
from integration.ghidra.session import GhidraEngineSession

installation = GhidraInstallation.discover()
with GhidraEngineSession(installation, "/path/to/binary") as session:
    summary = session.call("program.summary")
    first_page = session.call(
        "listing.window",
        {"start": summary["imageBase"], "direction": "forward", "limit": 128},
    )
```

Application hosts should normally use the [token-authenticated loopback gateway](../gateway/README.md)
instead of importing this supervisor or parsing Ghidra output themselves. Its CLI starts the same
session for one launch-time binary and exposes the canonical `/v1` envelopes:

```sh
python3 integration/gateway/gateway.py /path/to/binary \
  --ghidra-home /path/to/ghidra_12.1.2_PUBLIC \
  --ghidra-java-home /path/to/jdk-21 \
  --metadata-file /tmp/ghidraex-gateway.json
```

The metadata path must not exist. It is created owner-only, contains the private URL/token
descriptor, and is removed when the gateway exits. See the gateway documentation for the parent
lease, exact-Origin policy, and embedding API.

### Implemented methods

| Method | Behavior |
| --- | --- |
| `session.hello` | Negotiate protocol and advertised capabilities; the supervisor does this during `start()` |
| `session.ping` | Report liveness and echo a bounded nonce |
| `session.close` | Graceful idempotent close |
| `request.cancel` | Best-effort cancellation of queued or running work |
| `program.summary` | Return hashes, language/compiler identity, address extent, analysis state, and counts |
| `listing.window` | Page code units forward or backward with an opaque query-bound cursor |
| `symbols.search` | Search and page symbols deterministically |
| `references.list` | Page references to, from, or both directions around an address |
| `decompile.function` | Decompile the containing function with a caller-selected text bound |

`decompile.function` is advertised only when the worker initializes Ghidra's native decompiler.
All program methods carry the session's `{programId, revision}` context. Addresses and cursors are
opaque strings, not host-language integers.

### Lifecycle and failure behavior

The Java side has one serialized Ghidra API worker and a separate control reader, allowing
`request.cancel` and `session.close` to cancel an active monitor. Listing, symbol, and reference
loops check that monitor between bounded items; the decompiler receives the same monitor. The Python
side correlates concurrent callers, validates strict protocol envelopes, retains only bounded
launcher diagnostics, and fails pending requests on worker exit or invalid framing.

Startup failure and startup/shutdown deadlines terminate the child process group and remove the
temporary project. A request timeout reserves the original ID while it sends an out-of-band cancel.
The session remains available only if the operation reaches a terminal response within the bounded
`cancellation_grace` (one second by default). Otherwise the supervisor faults concurrent callers,
force-terminates the process tree, closes its pipes, and removes the project. Sessions do not restart
implicitly because every restart invalidates request IDs, cursors, event sequences, and program
context.

## Tests

Run all bridge tests with the Python standard library:

```bash
python3 -m unittest discover -s integration/ghidra/tests -v
```

Contract, discovery, fake-launcher, framing, lifecycle, and cleanup tests always run. Real tests
clearly report `skipped` when Ghidra or a GCC/Clang-compatible native C compiler is unavailable.
When available, the suite compiles `fixtures/tiny.c` and proves both paths:

- two fresh batch imports produce identical useful snapshot documents; and
- a persistent worker returns a real summary, paged listing, symbols, references, decompilation,
  ping, authenticated gateway response, replayed ready event, graceful close, and removed project.

Run the protocol and gateway suites independently when changing either boundary:

```sh
python3 -m unittest discover -s integration/protocol/tests -v
python3 -m unittest discover -s integration/gateway/tests -v
```

The repository's pinned real-Ghidra workflow runs all three suites against Ghidra 12.1.2 on Linux.
Adding another supported release or platform requires its own compatibility lane.

## Current boundary

Protocol v1 is persistent, interactive, and read-only. It proves the loader/analyzer, listing,
symbol, reference, decompiler, framing, gateway, and cleanup seams. Import and analysis still finish
before the persistent post-script starts, so pre-ready progress is not streamed. The gateway and
supervisor currently own one program each; multi-program orchestration is a layer above them.

Mutations and undo, debugger/trace state, real Ghidra-backed scripting and REPL execution, plugin
lifecycle, scoped agent credentials, OS sandboxing, and automatic
recovery are intentionally not smuggled into read methods. Their authority and state models are
defined in the [long-term engine architecture](../../docs/engine-architecture.md).
