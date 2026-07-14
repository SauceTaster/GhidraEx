# Advanced Ghidra workbench scenario pack

This pack selects a small set of difficult, repeatable scenarios for comparing the JavaFX, web,
IntelliJ, and VS Code/Code OSS workbenches. It is intentionally not a feature checklist. Each
scenario crosses several state owners and should expose architectural weaknesses in
synchronization, cancellation, transactions, persistence, or agent safety.

The companion [`advanced-scenarios.json`](advanced-scenarios.json) is a compact manifest that test
runners and synthetic engines can consume.

## Current prototype coverage

The new advanced surfaces intentionally cover lifecycle slices, not the full acceptance pack:

- JavaFX and web exercise the `TRACE-01` captured/past/emulator coordinate states and the
  `AGENT-01` capability/approval presentation model.
- IntelliJ exercises native debugger actions, `ConsoleView` scripting workflows, backend discovery,
  and plugin lifecycle matrices.
- VS Code exercises a native DAP lifecycle subset of `TRACE-01` plus a bounded pseudoterminal and
  `.gxscript` UI subset of `AGENT-01`.
- The real `analyzeHeadless` bridge covers a deterministic read-only part of `ASYNC-01`: import,
  analysis, timeout handling, process cancellation, and bounded program facts.

None of these slices claims live Trace/RMI, transactional program mutation, arbitrary PyGhidra or
Java execution, decompiler freshness, or third-party Ghidra extension activation. Those remain the
acceptance boundary for the persistent sidecar.

## Shared state model

All four workbenches should make these states observable and testable:

| Domain | Required states |
| --- | --- |
| Program | closed, importing, partially analyzed, ready, dirty, read-only, conflicted |
| Navigation | location, selection, and highlight as distinct values; connected, pinned, locked |
| Derived document | queued, current, stale, failed, cancelled |
| Task | queued, running, cancelling, cancelled, failed, completed |
| Transaction | preview, awaiting approval, applying, committed, rolled back, stale plan |
| Agent | idle, investigating, proposing, awaiting approval, executing, interrupted, stale, failed |
| Debugger | live, paused, past snapshot, emulator, unmapped, stale memory, write-disabled |
| Layout | focused, tabbed, split, detached, hidden, transient, restored |

Every event crossing the engine boundary should carry at least `programId`, `programRevision`, and
an operation/request ID. Debugger events additionally carry trace, thread, frame, and snapshot or
schedule coordinates. Responses from an older revision or request must not overwrite newer state.

## Golden scenarios

| ID | Priority | Scenario | Main architectural pressure |
| --- | --- | --- | --- |
| `SYNC-01` | P0 | Connected Listing, Decompiler, references, and snapshots | Many-to-many mappings and latest-wins async state |
| `EDIT-01` | P0 | Transactional type recovery and semantic edit cascade | Atomic mutation, invalidation, provenance, undo |
| `ASYNC-01` | P0 | Import and auto-analysis cancellation/recovery | Partial results, cancellation, failure, retry |
| `AGENT-01` | P0 | Evidence-backed batch change plan | Approval, stale plans, selective apply, audit |
| `GRAPH-01` | P1 | Function Graph stale/refresh/relayout/grouping | Semantic identity versus geometry and overlays |
| `DIFF-01` | P1 | Program Diff and Version Tracking review | Two-program synchronization and match state machines |
| `TRACE-01` | P1 | Debugger coordinates, time travel, and emulation | Atomic multi-coordinate state and irreversible effects |
| `DOCK-01` | P1 | Docking topology and agent-owned evidence workspace | Focus routing, lifecycle, and persistence |

### SYNC-01 — connected navigation and layered highlights

Fixture: `orbit-controller-v1` contains a `decode_packet` function where one decompiler expression
maps to several non-contiguous machine instructions. Open connected Listing, Decompiler, references,
and Inspector views, plus one pinned Decompiler snapshot.

Flow:

1. Navigate rapidly from function A to B to C while decompilation responses are deliberately
   returned in the order B, A, C.
2. Select `packet->payloadLength` in the Decompiler and then select the corresponding instructions
   in the Listing.
3. Navigate through an xref into a second program and use Back to restore the original program,
   address, field, scroll position, and token.
4. Keep the snapshot pinned to A while the connected views move to C. Lock the main Decompiler,
   rename a symbol, observe stale state, and manually refresh.
5. Add search, user, and agent highlight layers over the same token, then remove only the agent
   layer.

Acceptance checks:

- The final connected location is C; stale A/B responses are discarded and never flash into view.
- One user navigation creates one history entry and no cross-view feedback loop.
- The token-to-address mapping equals the fixture's exact address set in both directions.
- The pinned snapshot and locked document retain their own identities and freshness indicators.
- Removing an agent highlighter preserves user/search highlights, including blended overlaps.
- Cancellation, timeout, and an unavailable `HighFunction` produce an in-place diagnostic rather
  than a blank or silently stale view.

This follows Ghidra's separate location, selection, and highlight concepts in
[`ProgramLocation`](https://ghidra.re/ghidra_docs/api/ghidra/program/util/ProgramLocation.html),
[`Navigatable`](https://ghidra.re/ghidra_docs/api/ghidra/app/nav/Navigatable.html), and the
[`DecompilerHighlightService`](https://ghidra.re/ghidra_docs/api/ghidra/app/decompiler/DecompilerHighlightService.html).

### EDIT-01 — transactional type recovery and semantic propagation

Fixture: `decode_packet` is called from at least three sites and uses bytes suitable for a packed
`PacketHeader` structure containing scalar, pointer, array, and bitfield members.

Flow:

1. Preview renaming the function and parameters, changing its calling convention/signature, and
   applying `PacketHeader` over a selected memory range.
2. Exercise invalid names, duplicate symbols, an incompatible fixed-size type, and a conflicting
   imported data type. Validation errors must be non-mutating.
3. Approve a subset of the proposal and apply it as one named transaction.
4. Observe Listing, Decompiler, Symbol Tree/Table, callers, xrefs, Structure Editor, and typed
   instances refresh from the same committed revision.
5. Undo once, redo once, then mutate an indirectly referenced type and verify that incompatible
   editor-local undo history is invalidated.

Acceptance checks:

- No program mutation occurs while a form is invalid or approval is pending.
- The accepted group creates exactly one undo item; one Undo restores every dependent surface.
- Packed/unpacked and little/big-endian fixture layouts produce the expected size, offsets, and
  bit positions.
- Cancelling or failing any validation leaves no half-created types, names, or history entry.
- Agent-created names and markup use `SourceType.AI`; they never overwrite `USER_DEFINED` or
  `IMPORTED` markup without a separately approved conflict decision.
- A decompiler result generated before the commit cannot replace the post-commit result.

Ghidra's database-facing decompiler helpers explicitly require a fresh decompilation after edits
([`HighFunctionDBUtil`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/pcode/HighFunctionDBUtil.html)).
Data types have conflict policies and change listeners
([`DataTypeManager`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/data/DataTypeManager.html)),
and persistent programs provide transactions plus undo/redo
([`DomainObject`](https://ghidra.re/ghidra_docs/api/ghidra/framework/model/DomainObject.html)).

### ASYNC-01 — import and analysis lifecycle

Fixture: one recognized PE/ELF program, one raw image requiring a language/compiler choice, and
deterministic analyzers with controllable latency, failure, and cancellation checkpoints.

Flow:

1. Import both fixtures, review detected metadata, resolve the raw-image prompt, and choose a
   non-default analyzer set.
2. Start analysis; allow symbols, functions, and xrefs to stream as partial results while the UI
   remains navigable.
3. Cancel at a deterministic checkpoint, inspect the partial-but-valid model and analyzer log,
   then resume/re-run.
4. Inject one analyzer failure and one decompiler timeout; retry the failed operation without
   restarting the workbench.
5. Close the program during a queued derived-document request.

Acceptance checks:

- Task progress is monotonic, phase-labeled, and cancellable; cancellation acknowledgement is
  bounded and the workbench remains responsive.
- Partial state is explicitly marked and is internally consistent after cancellation.
- Re-running is idempotent: it neither duplicates symbols nor loses accepted user/AI markup.
- Failed analyzers expose a navigable log/error record and do not mark the entire program ready.
- Late progress/results for a cancelled, closed, or superseded request are ignored.
- Retry creates a new operation ID and reaches the same deterministic completed model.

Ghidra's [`TaskMonitor`](https://ghidra.re/ghidra_docs/api/ghidra/util/task/TaskMonitor.html) requires
long work to check cancellation and publish progress, while analyzers receive that monitor and a
message log ([`Analyzer`](https://ghidra.re/ghidra_docs/api/ghidra/app/services/Analyzer.html)).

### AGENT-01 — evidence-backed batch change plan

Fixture: the program contains a parser path with several plausible names/types, one deliberately
ambiguous indirect call, and a concurrent-edit switch that increments the program revision after a
plan is produced.

Flow:

1. Ask the agent to explain the selected function and rank vulnerability hypotheses. Every claim
   must carry clickable function/address/token evidence and be labeled fact or hypothesis.
2. Produce a spreadsheet-like rename/retype/comment/bookmark plan with old/new values, confidence,
   impact on callers, validation, provenance, and per-row selection.
3. Approve selected rows, then trigger a concurrent edit before execution. Applying the stale plan
   must be blocked and require rebase/review.
4. Recompute, approve, apply atomically, validate postconditions, and expose one Undo item.
5. Generate a script for the same operation, show its exact source/hash and declared capabilities,
   run it in a disposable read-only project, and compare its dry-run result with the UI plan.

Acceptance checks:

- Read/navigation work needs no mutation approval; external data/model egress is a separate,
  explicit permission.
- No transaction remains open while the agent or user is thinking or while approval is pending.
- Execution rechecks `programRevision` immediately before opening a transaction.
- Partial row approval changes only selected rows; a mid-execution exception or cancellation rolls
  back the whole approved group.
- The audit record contains program hash/revision, evidence, model/prompt version, exact changes,
  approval identity/time, transaction name, outcome, and resulting revision.
- Agent output is `SourceType.AI`, visually distinguishable, filterable, and lower priority than
  user/imported markup.
- Read-only headless mode is treated as project-data protection, not as a Java code sandbox; file,
  network, subprocess, debugger, and remote-model capabilities remain separately declared/gated.

Current Ghidra explicitly defines `SourceType.AI` for AI-assisted content and ranks it below user
and imported markup ([`SourceType`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/symbol/SourceType.html)).
Headless processing can discard project changes
([`HeadlessOptions`](https://ghidra.re/ghidra_docs/api/ghidra/app/util/headless/HeadlessOptions.html)),
but generated code still requires an actual sandbox.

### GRAPH-01 — semantic graph state versus layout state

Fixture: a function with a switch, nested loops, 30–50 basic blocks, and deterministic paths. A
second oversized graph exceeds a configured node cap.

Flow:

1. Edit control flow and verify that the existing graph becomes stale without moving vertices.
2. Refresh while preserving geometry, then Relayout and verify that semantic identity survives
   changed coordinates.
3. Group, nested-group, recolor, and ungroup blocks.
4. Apply focus, hover, loop, and agent path overlays concurrently; convert one path to a program
   selection.
5. Close/reopen the connected graph and a snapshot graph; trigger the oversized graph.

Acceptance checks:

- Refresh preserves coordinates within tolerance; Relayout changes geometry but not vertex/edge IDs.
- Group/ungroup restores the original semantic graph and deterministic color inheritance.
- Each overlay has its own owner/lifetime; dismissing the agent overlay changes no user state.
- Path-to-selection equals the fixture's exact code-unit address set.
- Connected zoom/pan/group state restores; snapshot-only mutations do not leak into it.
- Node-cap cancellation ends in a visible, non-blocking error with no partially interactive graph.

These states mirror the official
[`Function Graph` help](https://github.com/NationalSecurityAgency/ghidra/blob/master/Ghidra/Features/FunctionGraph/src/main/help/help/topics/FunctionGraphPlugin/Function_Graph.html).

### DIFF-01 — two-version review and Version Tracking

Fixture: `orbit-controller-v1` and `orbit-controller-v2` share compatible address spaces and contain
exact, ambiguous, implied, and competing function/data matches plus byte, symbol, comment,
reference, function, and context differences.

Flow:

1. Open synchronized source/destination listings and filter difference categories.
2. Navigate differences, preview direction and affected markup, then merge selected safe categories.
3. Create a Version Tracking session, run preconditions and deterministic correlators, and sort by
   score, confidence, votes, and length delta.
4. Accept `A↔X`, verify competitors `A↔Y` and `B↔X` become blocked, and apply selected markup.
5. Change filters so the accepted row disappears; exercise identity-, index-, and no-selection
   tracking policies. Clear the match and undo destination markup.

Acceptance checks:

- Both program/revision identities and merge direction remain visible at all times.
- Accept/block transitions are atomic and the markup substate is explicit.
- Bulk exact-match work is cancellable and cannot leave half-applied associations.
- Ambiguous/duplicate matches are never auto-applied; agent ranking stays advisory.
- Clear/Undo restores fixture destination names, comments, references, and match states.
- Incompatible address spaces fail preconditions before any mutable session work.

Ghidra exposes category-specific differences through
[`ProgramDiffFilter`](https://ghidra.re/ghidra_docs/api/ghidra/program/util/ProgramDiffFilter.html),
and its official
[`Version Tracking workflow`](https://github.com/NationalSecurityAgency/ghidra/blob/master/Ghidra/Features/VersionTracking/src/main/help/help/topics/VersionTrackingPlugin/VT_Workflow.html)
distinguishes exact, implied, duplicate, accepted, and blocked matches.

### TRACE-01 — debugger coordinates, time travel, and emulator-first changes

Fixture: a captured trace with two threads, three stack frames, several snapshots, static mappings,
stale/changed memory and registers, plus an emulator fork.

Flow:

1. Switch trace, thread, frame, and snapshot while Dynamic Listing, Memory, Registers, Stack,
   Watches, and Decompiler are open.
2. Navigate to a past sparse snapshot, inspect stale memory, then switch between control-target,
   control-trace, and emulator modes.
3. Ask the agent to propose a breakpoint, bounded step plan, register change, and conditional-branch
   patch. Execute it in the emulator first and step backward/forward.
4. Attempt the same operation against the live target with writes disabled, then explicitly approve
   only the breakpoint while rejecting memory/register writes.
5. Terminate or resume the target while approval is visible.

Acceptance checks:

- A coordinate change commits atomically across all dependent views; old variable evaluations are
  dropped rather than applied to the new frame/snapshot.
- Past/stale/changed/unmapped values are visually and accessibly distinguishable.
- Trace/database edits are undoable, while the UI clearly states that external target effects are
  not reliably reversible.
- Live mutation controls remain disabled until the correct mode and scoped approval are both active.
- Target state is revalidated after approval; a resumed/terminated target makes the action stale.
- Audit captures target, trace, snapshot/schedule, thread/frame, before/after bytes, stop condition,
  timeout, and result.

Ghidra models current debugger state as trace/thread/frame/time coordinates in its
[`Debugger navigation` course](https://ghidra.re/ghidra_docs/GhidraClass/Debugger/A5-Navigation.html).
Its machine-state guide documents stale/changed values and write locks
([`Machine State`](https://ghidra.re/ghidra_docs/GhidraClass/Debugger/A4-MachineState.html)), while
the emulator supports schedules and backward navigation
([`Emulation`](https://ghidra.re/ghidra_docs/GhidraClass/Debugger/B2-Emulation.html)).

### DOCK-01 — persistent topology and agent-owned workspace

Fixture: a nested split with Listing/Decompiler, tabbed references/graph, a detached Inspector, a
transient search result, and an agent-owned evidence workspace.

Flow:

1. Move focus through each provider and invoke the same context-sensitive shortcut.
2. Serialize/reopen the topology, including tab order, selected tabs, split ratios, pinned/locked
   state, and detached bounds.
3. Close/restore a persistent provider and close a transient search provider.
4. Exercise Close Others/Left/Right, then reopen on one monitor with the saved detached window
   deliberately off-screen.
5. Ask the agent to open and later close its evidence workspace without changing the user's base
   layout.

Acceptance checks:

- Exactly one focused provider owns local actions after every focus transition.
- Provider IDs/order and split ratios round-trip within tolerance; off-screen windows are rehomed.
- Persistent providers retain state when hidden/restored; transient providers are disposed.
- Tab-close operations affect exactly the documented tabs.
- Agent-owned providers are tagged and removable as a group; closing them restores the base topology
  byte-for-byte and does not steal final focus.

These behaviors are grounded in Ghidra's official
[`Docking Windows` help](https://github.com/NationalSecurityAgency/ghidra/blob/master/Ghidra/Features/Base/src/main/help/help/topics/DockingWindows/Docking_Windows.htm).

## Agent use-case matrix

| Use case | Default capability | Approval boundary | Golden scenario |
| --- | --- | --- | --- |
| Explain selection with evidence links | Local read and navigation | Remote model/data egress | `SYNC-01` |
| Rank parser/vulnerability hypotheses | Local analysis and temporary overlays | Persist bookmarks/comments or use network BSim | `AGENT-01`, `GRAPH-01` |
| Batch rename/retype/signature plan | Non-mutating preview | Selected rows or named atomic group | `EDIT-01`, `AGENT-01` |
| Generate and dry-run a script | Generate/review; disposable read-only run | New source hash and each external capability; production write | `AGENT-01`, `ASYNC-01` |
| Review two-version matches | Read-only diff and ranking | Exact merge direction, categories, ranges, conflicts | `DIFF-01` |
| Debugger/patch copilot | Captured trace reads and emulator plan | Attach/launch, live reads with side effects, resume/step, every live write | `TRACE-01` |

## Mutation safety contract

All agent-authored mutations should follow the same contract:

1. Build a read-only plan against a recorded program revision.
2. Show exact before/after values, affected objects, evidence, validation, and provenance.
3. Obtain approval before opening a transaction; never wait for a person or model inside one.
4. Recheck the revision immediately before execution.
5. Open one serialized transaction, mark it abort-on-close, apply only approved changes, and validate
   postconditions before explicit commit.
6. Publish a named Undo item and a complete audit record.

```java
if (program.getModificationNumber() != plannedRevision) {
    throw new StalePlanException();
}

try (Transaction tx = program.openTransaction("AI: approved semantic edits")) {
    tx.abortOnClose();
    applyApprovedChanges();
    validatePostconditions();
    tx.commit();
}
```

The abort call matters: Ghidra's [`Transaction`](https://ghidra.re/ghidra_docs/api/db/Transaction.html)
commits on close by default. Agent writes must be serialized because aborting a sub-transaction can
roll back its larger transaction. Live-debugger effects require a separate model because program
undo cannot restore arbitrary external target state.

## Deterministic fixture contract

The synthetic engine should grow from one program into a reproducible fixture family:

- `orbit-controller-v1`: 100,000 instructions, parser call graph, complex CFG, structure-friendly
  memory, multi-instruction decompiler tokens, and known xrefs.
- `orbit-controller-v2`: compatible address spaces plus controlled byte, function, symbol, comment,
  reference, type, and context differences; exact and ambiguous Version Tracking matches.
- `orbit-controller.trace`: two threads, three frames, six snapshots, one sparse snapshot, changed
  registers/memory, static mappings, and a deterministic emulator schedule.
- Fault controls: delayed/out-of-order decompilation, analyzer failure/cancel point, graph node cap,
  type/name conflict, revision race, script exception, stale debugger coordinate, and target resume.

Expected address sets, graph edges, layouts, match transitions, type offsets, and before/after hashes
belong in fixture data rather than UI test code.

## Test layers and implementation order

Each scenario should be exercised at four layers:

1. Engine contract tests for paging, revisions, cancellation, transactions, provenance, and faults.
2. Headless state-model tests for transitions and stale-result suppression.
3. Component tests for mapping, filtering, validation, focus/action routing, and persistence.
4. End-to-end UI tests using the same scenario IDs and fixture assertions in all four workbenches.

Implement `SYNC-01`, `EDIT-01`, `ASYNC-01`, and `AGENT-01` first. Together they force the engine
boundary to support the hard parts—versioned documents, address mappings, transactions, provenance,
and safe agent execution—before graph, diff, debugger, and docking-specific work widens it.
