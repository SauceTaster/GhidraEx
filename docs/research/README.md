# GhidraEx architecture research

This directory is the maintained design corpus for the JavaFX, IntelliJ Platform, and VS Code /
Code OSS product ports. It records constraints discovered in Ghidra itself, candidate solutions,
tests that would disprove those solutions, and the decisions that follow.

The existing [engine architecture](../engine-architecture.md) and
[protocol v1](../../integration/protocol/PROTOCOL.md) remain the implementation record for the
bounded, immutable, one-program read path. They are not the target model for repository writes,
merge, debugging, scripts, plugins, or agents. Those features require the v2 direction captured
here and in [ADR-0002](../../integration/protocol/ADR-0002-semantic-protobuf-project-runtime.md).

## Documents

| Document | Purpose | Maturity |
| --- | --- | --- |
| [Edge-case ledger](edge-case-ledger.md) | Stable IDs for Ghidra behaviors that can break a port, with mitigations and conformance tests | Living research record |
| [Runtime topology](runtime-topology.md) | Process, project, debugger, snapshot, extension, and state ownership | Proposed architecture |
| [Protocol v2 requirements](protocol-v2-requirements.md) | Semantic Protobuf model, identities, operations, jobs, streams, and evolution rules | Requirements before schema work |
| [Host integration](host-integration.md) | Native boundaries and unavoidable tradeoffs for JavaFX, IntelliJ, and VS Code | Port guidance |
| [Agent safety](agent-safety.md) | Evidence, approval, transaction, capability, and audit model for agentic workflows | Proposed policy |
| [Conformance plan](conformance-plan.md) | Shared fixtures, fault injection, compatibility gates, and release criteria | Test plan |
| [Research backlog](research-backlog.md) | Prioritized unanswered questions and exit criteria | Living backlog |

## Current architectural position

1. Keep the three product hosts: JavaFX, IntelliJ Platform, and VS Code / Code OSS.
2. Preserve Ghidra's analysis and data model behind an upstream-compatible process boundary. Do
   not make a broad UI fork responsible for reimplementing program semantics.
3. Make a `ProjectRuntime`, not a program worker, the authoritative mutable unit. It owns one local
   project lock, repository identity, Ghidra build, extension set, and all live domain objects.
4. Give debugging a separate `DebugRuntime`. A TraceRMI connection may own multiple targets and
   traces, and its time, memory, and control semantics do not fit a program-session abstraction.
5. Run expensive or untrusted derived work in disposable workers over immutable exports. No two
   processes open the same project directory.
6. Keep one visible Swing compatibility surface for workflows that cannot initially be made
   semantic, especially repository merge and extension-defined dialogs. It is an escape hatch,
   never the canonical UI.
7. Freeze JSON v1 as a compatibility slice. Define v2 in Protobuf with full native gRPC for desktop
   clients and a browser-safe unary/server-stream adapter. The data contract is semantic and is not
   derived from Ghidra Java classes.
8. Expose intent-level operations with previews, expected generations, staged outcomes, and audit
   records. Do not expose raw transactions or arbitrary Java execution to UI or agent clients.

## Research method

The baseline is Ghidra **12.1.2**. Findings use the official 12.1.2 Javadocs or source links pinned
to the `Ghidra_12.1.2_build` tag whenever possible. Host claims use the official OpenJFX,
JetBrains Platform, VS Code, gRPC-Web, and Protobuf documentation. A moving `master` link is not
evidence for a version-specific claim.

Each ledger item should contain:

- a stable ID and priority;
- the observed behavior, including whether it is API contract or implementation detail;
- what breaks if a port treats the behavior as simpler than it is;
- a candidate containment or semantic model;
- at least one reproducible conformance test;
- a primary source pinned closely enough to re-audit on upgrade.

The solution labels mean:

| Label | Meaning |
| --- | --- |
| `REQUIRED` | An architectural invariant; violating it is a release blocker |
| `PROPOSED` | The leading solution, still requiring a proof or prototype |
| `COMPAT` | Initially served by the controlled legacy Swing surface |
| `OPEN` | The Ghidra behavior is understood but the product solution is not settled |

## Upgrade discipline

Every supported Ghidra build gets a new engine fingerprint and a ledger delta review. A worker
fleet must not mix builds against one writable repository during a rolling upgrade. Schema and
capability negotiation can allow old and new frontends to coexist, but storage conversion,
language/compiler-spec changes, extension changes, and repository content compatibility require a
separate maintenance decision.

Research is complete for a subsystem only when its identity, lifetime, concurrency, cancellation,
recovery, persistence, security, and host-presentation behaviors all have executable tests. An API
inventory or a successful happy-path demo is not enough.
