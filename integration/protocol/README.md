# GhidraEx engine protocol

This directory is the compatibility boundary between Ghidra and every GhidraEx host. The
protocol is deliberately independent of Ghidra's Java classes: a host speaks JSON to a supervised
engine process or to a token-authenticated loopback gateway and receives immutable data transfer
objects.

Version 1 is a bounded, read-only protocol. Stdio frames carry a fresh supervisor-supplied 128-bit
session nonce, and `session.ready` carries the launch program's SHA-256 for supervisor verification.
It exposes session lifecycle, program metadata, listing windows, symbol search, decompilation,
references, and cooperative cancellation. Debugger, scripting, mutation, and extension-management
methods are intentionally absent until their state, authority, and recovery semantics are specified.

Files:

- [`ADR-0001-process-isolated-engine.md`](ADR-0001-process-isolated-engine.md) records the
  accepted v1 process-isolation decision and constraints.
- [`ADR-0002-semantic-protobuf-project-runtime.md`](ADR-0002-semantic-protobuf-project-runtime.md)
  is the proposed v2 direction for mutable project-owner runtimes and semantic Protobuf.
- [`PROTOCOL.md`](PROTOCOL.md) is the normative v1 wire specification.
- [`schemas/`](schemas/) contains JSON Schema 2020-12 envelope and core-method schemas.
- [`contract.py`](contract.py) is a dependency-free reference codec and strict runtime validator.
- [`tests/`](tests/) contains dependency-free contract and schema-integrity tests.

Run the focused suite with:

```sh
python3 -m unittest discover -s integration/protocol/tests -v
```

The JSON Schemas are the language-neutral artifact. `contract.py` is a reference implementation,
not a requirement that other clients use Python.

JSON remains canonical for v1 only. The source-audited v2 requirements, identities, jobs,
authority model, host constraints, and conformance plan are maintained in
[`docs/research`](../../docs/research/README.md). No v2 `.proto` files exist yet; ADR-0002 remains
proposed until its required vertical-slice proofs pass.

## Implemented adopters

- [`../ghidra/session.py`](../ghidra/session.py) supervises the persistent nonce-framed worker and
  verifies the launch binary digest before negotiating the session.
- [`../gateway/gateway.py`](../gateway/gateway.py) exposes the same envelopes over authenticated
  loopback HTTP, retains a bounded event ring, and owns the engine session lifetime.
- [`../ghidra/tests/test_real_session.py`](../ghidra/tests/test_real_session.py) exercises the
  contract against a real imported binary, including paging, symbols, references, decompilation,
  HTTP transport, ready-event replay, and cleanup.
- [`../../prototypes/web-workbench/src/api.ts`](../../prototypes/web-workbench/src/api.ts) is the
  first host adapter: it negotiates through a server-side authenticated proxy, preserves opaque
  addresses and program context, and never substitutes fixture data after a real-session failure.

The implementation covers the complete v1 read surface and its normative deadline escalation:
cooperative cancellation can preserve a session, while an ignored cancel force-terminates and
invalidates the owned worker before any host may retry.
