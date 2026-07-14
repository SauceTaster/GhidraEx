# ADR-0002: Semantic Protobuf v2 and project-owner runtimes

- Status: proposed
- Date: 2026-07-13
- Decision owners: GhidraEx maintainers
- Supersedes: no accepted decision; narrows the evolution path after ADR-0001

## Context

ADR-0001 proved a useful process boundary for one immutable imported program. Research into Ghidra
projects, repositories, merge, version tracking, debugging, scripting, extensions, program
addresses, listing, and decompilation shows that the same session model cannot safely acquire write
authority.

A writable Ghidra project is a process-owned state graph. It has a local project lock, global
active-project and repository state, live domain-object consumers, private user data, recovery
data, per-file version control, and compound workflows. Repository operations are not atomic and
can have unknown outcomes after reconnect. Debugger time and authority are also independent of a
program revision. JSON v1 addresses and flattened listing/decompiler values omit distinctions that
must become stable before native hosts build durable UI state around them.

## Proposed decision

1. Freeze protocol v1 as a bounded, read-only compatibility protocol.
2. Make one `ProjectRuntime` the sole writable owner of a local project for a specific user,
   repository identity, Ghidra build, and extension digest.
3. Define protocol v2 in Protobuf from semantic product concepts, not generated or serialized
   Ghidra Java classes.
4. Use native gRPC for desktop/backend clients. Provide a browser adapter limited to unary and
   server-streaming calls, with explicit job/control methods where client or bidirectional streams
   would otherwise be required.
5. Separate `DebugRuntime`, immutable snapshot workers, sandboxed script runners, and an optional
   visible Swing compatibility runtime from the project owner.
6. Accept intent-level mutation operations only. The runtime owns Ghidra transactions; repository
   effects use durable sagas and staged outcomes.
7. Make identity, generation, epoch, scope, authority, completeness, and provenance first-class in
   every applicable request and result.

## Consequences

### Positive

- JavaFX, IntelliJ, and VS Code share one behavior contract without sharing a JVM or UI toolkit.
- Repository ambiguity, lost replies, recovery, and partial batch outcomes become representable.
- Browser limitations do not reduce the semantic model used by desktop clients.
- Agent operations can be previewed, approved, audited, and stale-checked without exposing raw
  Ghidra transactions or arbitrary code execution.
- Result-local entities such as decompiler tokens, graph nodes, and dynamic symbols can expire
  safely instead of masquerading as durable object IDs.

### Costs

- v2 is a new implementation rather than a mechanical JSON-to-Protobuf encoding.
- The broker needs durable identity overlays, event epochs, operation journals, and result arenas.
- Some flows require compound jobs and several round trips instead of a single method call.
- Legacy Swing merge and extension UI remain a controlled compatibility obligation until semantic
  replacements exist.
- Native gRPC and the browser adapter need shared conformance fixtures to prevent drift.

## Rejected alternatives

### Translate the current JSON fields directly to Protobuf

This keeps scalar revision, string address, flattened listing, and one-program session assumptions.
It saves initial schema work while locking in the wrong identities and omission semantics.

### One multi-tenant Ghidra JVM

Ghidra has process-global active project, repository authentication/cache, theme, preference,
window, and extension/class-loader state. Project locking and arbitrary extension code make a JVM
an unsuitable tenant boundary.

### Raw remote transactions

Holding a transaction while a client thinks, disconnects, or requests approval creates lock and
rollback hazards. Nested Ghidra transactions share fate, and repository operations are outside
their atomic boundary.

### Treat the project tree as a filesystem

The tree overlays local and repository layers, permits a file and folder with the same sibling
name, supports side-effecting links, and can reveal a shadowed remote object after local deletion.
VS Code `FileSystemProvider` and IntelliJ VFS may offer projections, but cannot be canonical.

## Required proofs before acceptance

- Two independent native clients pass the same Protobuf conformance fixtures and stale-reference
  tests.
- The browser adapter passes the same unary and event semantics without bidirectional streaming.
- A project owner survives reconnect and reconciles lost checkout/move/check-in replies without a
  blind retry.
- Compound merge or VT jobs demonstrate fixed ownership, cancellation, recovery, and explicit
  partial outcomes.
- A decompiler/listing implementation preserves structured addresses, sparse gaps, offcuts,
  result-local references, completeness, and generation checks.
- An agent proposal becomes exactly one server-owned Ghidra transaction after approval and is
  rejected after a concurrent generation change.
- The compatibility runtime detects and surfaces modal legacy UI instead of deadlocking a hidden
  worker.

Detailed requirements and tests live in
[`docs/research`](../../docs/research/README.md).
