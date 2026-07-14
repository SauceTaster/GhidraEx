# Conformance and fault-injection plan

## Purpose

The prototypes prove that each UI can present useful workflows. Conformance must prove that the
three hosts make the same semantic decisions under Ghidra's difficult states, including failures
that rarely occur in a demo. The existing [advanced scenario pack](../advanced-scenario-pack.md)
remains the workflow-level UX suite; this document defines the lower-level engine, protocol,
adapter, recovery, and safety gates behind it.

Every ledger ID in [the edge-case ledger](edge-case-ledger.md) must eventually map to one or more
test IDs. No feature is “covered” solely because its happy path is shown in a prototype.

## Test layers

| Layer | Runs | Proves |
| --- | --- | --- |
| Schema/wire | generated messages in Java, TypeScript, and broker language | presence, unknown fields/enums, bounds, canonical fixtures, v1/v2 separation |
| Semantic reducer | recorded snapshots/events through each host-neutral adapter | ordering, stale rejection, resync, partial/error/indeterminate states |
| Fake runtime | deterministic in-memory project/debug/job models | host commands, prompts, cancellation races, reconnect, authority without Ghidra latency |
| Real API fixture | pinned Ghidra build over generated projects/programs/traces | actual identity, transaction, listing, analysis, import, decompiler and persistence behavior |
| Repository fixture | disposable real Ghidra server and at least two identities | checkout/check-in/merge, reconnect, lost events/replies, history and permissions |
| Fault-injected runtime | proxies/hooks at storage, repository, process, native decompiler, artifact and target boundaries | staged failure, rollback, partial and unknown outcomes, cleanup |
| Host integration | JavaFX TestFX-equivalent harness, IntelliJ fixture, VS Code desktop/web extension tests | native action routing, capabilities, focus, accessibility, secrets, remote placement |
| End to end | packaged host + broker + exact engine/runtime | launch, upgrade, crash/restart, real workflow, resource and security boundaries |

Golden fixtures encode semantic records and invariants, not pixel screenshots or Ghidra database
keys. Screenshots are useful for visual regression but cannot define address, identity, authority,
or transaction correctness.

## Fixture catalog

### `project-layer-zoo`

A disposable local/shared project containing:

- private, checked-out, versioned, hijacked, hidden/revealed and recovery-bearing entries;
- a file and folder with the same sibling name;
- internal links, external links, two aliases to one target, and an independent-link cycle;
- at least three repository versions with a scenario that deletes and reuses the latest number;
- `ProgramUserData` with primitive and extension-defined properties;
- two repository users with different permissions and a second server identity.

Primary gates: `PRJ-001` through `PRJ-013`, `PRJ-017` through `PRJ-019`, and native project trees.

### `compound-workflow-zoo`

Two compatible programs, one intentionally incompatible program, a VT session, and merge changes
covering bytes, symbols, comments, context, functions, datatypes, references, bookmarks, source
maps, and user properties. Include exact, ambiguous, implied, competing and stale VT matches.

Primary gates: `PRJ-003`, `PRJ-006`, `PRJ-014` through `PRJ-016`, `MRG-001` through `MRG-002`,
`VT-001` through `VT-005`, crash-between-save behavior, fixed compound leases, partial/degraded
results, non-invertible actions, and legacy merge parity.

### `address-and-memory-zoo`

Programs/languages with unsigned high-bit addresses, signed stack values, word addressing,
segmented logical aliases, overlays, register/external/constant/unique spaces, multiple loaded
spaces, sparse initialized/uninitialized ranges, byte/bit-mapped aliases, loaded/non-loaded blocks,
original file bytes, patches, and relocations in several states.

Primary gates: `MOD-001` through `MOD-006`, cross-language Protobuf precision, resolver ambiguity,
and memory provenance.

### `listing-flow-zoo`

A multi-gigabyte sparse address layout with instructions/data separated by gaps, instruction and
data offcut references/symbols, length-overridden parsed overlap, delay slots, fallthrough/flow
overrides, calls/tail calls/thunks/indirect calls, noncontiguous functions, multi-entry and
overlapping block-model outputs.

Primary gates: `MOD-010`, `MOD-011`, `DER-004`, `DER-005`, `DER-007`, `DER-011`, paging performance,
and exact CFG policy metadata.

### `datatype-and-source-zoo`

Conflicting built-in/program/archive types, identical names with different lineage, packed and
unpacked structures, both endians, unions, overlapping bitfields, zero-length/flexible/dynamic
components, recursive dependencies, component default comments, many-to-many source maps, and
several program trees with multiple parents.

Primary gates: `MOD-012` through `MOD-015`, `DTA-001` through `DTA-005`, `DER-006`, `DER-008`,
epoch-qualified archive handles, structural sync verification, conflict previews, partial prefix
outcomes, degraded placeholders and cascade invalidations.

### `decompiler-race-zoo`

Functions large enough to exercise limits, jump tables, variable split/merge, synthetic functions,
output-language/format changes, custom compiler-spec prototypes/fixups, native decompiler crash,
hang, cancellation, bad response, warning, mixed-generation mutation, and pool saturation.

Primary gates: `DER-001` through `DER-003`, result arenas, `LIVE` versus `CONSISTENT`, profile cache
keys, and JVM watchdog recovery.

### `import-artifact-zoo`

Recognized and raw binaries, a container/nested filesystem with several load specs, multiple output
domain objects, recursive libraries, password-protected content, name conflicts, malformed archives,
PDB/DWARF candidates that are exact/mismatched/compressed/corrupt/credentialed, C parser include
environments, and compatible/incompatible BSim providers.

Primary gates: `MOD-007`, `MOD-018` through `MOD-020`, mount/resource cleanup, trust, network and
secret isolation, deterministic plan/output manifests, and cancellation at every stage.

### `analysis-language-zoo`

Deterministic analyzers with explicit priority, dependencies, options, latency, new-work scheduling,
partial mutation, cancellation, and failure. Include language/compiler migrations that remove
spaces/registers/context and custom compiler-spec extensions.

Primary gates: `MOD-003`, `MOD-008`, `DER-012`, exact option/default fingerprints, quiescence,
single analysis ownership and migration recovery.

### `extension-script-zoo`

Engine-only, portable-semantic, Swing UI, and host-specific extensions; duplicate/missing service
providers; arbitrary modal dialog; primitive and object property maps; plugin bookmark styling; a
script that throws after mutation, a multi-program script, sandboxed generated source, and a REPL
that attempts to retain handles/secrets/child processes after reset.

Primary gates: `EXT-001` through `EXT-008`, runtime restart/fingerprint, prompt broker, opaque data,
script diff/proposal path, and sandbox revocation.

### `debugger-trace-zoo`

A recorded trace and controllable fake/real TraceRMI target with:

- raw snaps, unrelated schedules, thread steps, p-code steps, patches and scratch materializations;
- known/unknown/error memory pages and partial live reads;
- two targets/traces on one connection and disconnect/reconnect injection;
- logical breakpoints with pending/multi-target/inconsistent locations;
- guest/native platform mappings, unmappable ranges, emulation cancellation and live-fill policy;
- delayed/lost replies for every authority-bearing target command.

Primary gates: `DBG-001` through `DBG-012`, explicit control authority, no automatic live retry,
partial/provenance-bearing results, and native debugger presentation.

### `agent-adversarial-zoo`

Programs/artifacts containing instruction-like malicious text, ambiguous evidence, incomplete
queries, stale dynamic/result-local refs, user/imported markup conflicts, 100-row selective plans,
concurrent edits, target focus changes, secret-bearing errors, egress-policy revocation, and
generated code that requests undeclared capabilities.

Primary gates: [agent safety](agent-safety.md), approval binding, stale plans, evidence coverage,
data egress and tamper-evident audit.

## Fault injection points

Hooks are named and deterministic so a test can fail immediately before or after a side effect:

| Boundary | Required hooks |
| --- | --- |
| Project storage | before/after open, recovery decision, transaction commit, save, close, migration step, identity repair |
| Repository | before send, after server effect/before reply, reconnect, event loss/reorder, snapshot listing error, each batch item/stage |
| Domain object | concurrent generation advance, undo/redo/restore, consumer disconnect, fixed-group membership change |
| Native decompiler | queue saturation, startup failure, timeout, cancellation, process crash, malformed/truncated response, disposal hang |
| Analysis | before first mutation, after partial commit, child scheduling, cancellation checkpoint, analyzer exception, quiescence race |
| Import/filesystem | candidate probe, mount, password, each output, dependency recursion, conflict, save, unmount, malformed archive entry |
| Artifact/network | DNS/connect/auth, mismatched identity, partial download, decompression, digest failure, cache race, cancellation |
| Export | source snapshot, temporary create, partial write, validate, publish/rename, client download, cancellation |
| Script/extension | class discovery, duplicate provider, modal prompt, capability denial, quota, exception, child process, runner kill |
| Debug/target | target event/reply loss, delayed completion, disconnect, partial memory page, platform remap, emulation write-down, live-fill attempt |
| Broker/transport | event gap, stream reconnect, duplicate unary request, deadline, client disconnect, expired lease, restart/epoch advance |

A hook after the external effect but before the reply is essential. Without it, tests cannot prove
`OUTCOME_UNKNOWN` and reconciliation behavior.

## Protocol compatibility suite

The same binary fixture corpus is consumed by every supported language binding and transport:

- absent versus explicit zero/default, `EMPTY` versus `WHOLE`, unknown enum values and fields;
- max unsigned address bits, signed stack values, segmented and word-address metadata;
- same-name file/folder refs and link edge/target refs;
- every completeness, cancellation, partial, stale, resync and indeterminate outcome;
- deterministic paging and invalid/expired/query-mismatched cursors;
- event sequence gap followed by full atomic snapshot replacement;
- bounded strings/bytes/repeated fields/nesting and invalid Unicode/oversize rejection;
- credentials and sensitive paths absent from messages, errors, logs and recorded fixtures;
- old frontend/new broker and new frontend/old broker capability negotiation;
- native gRPC and browser unary/server-stream adapter semantic equivalence.

Generated bindings are tested, but tests also assert semantics above the serializer. A message can
round-trip perfectly while still confusing absent with whole scope or safe retry with unknown
outcome.

## Host adapter suite

All hosts replay an identical state/event script and report a normalized action log. Required
sequences include:

1. B, A, C request completion while navigation requests A, B, C; only C becomes current.
2. Event sequence gap during a dirty view; UI marks stale/resyncing and swaps one coherent snapshot.
3. Runtime restart advances epoch; old rows, cursors, documents and pending proposals fail visibly.
4. Project tree shows same-name file/folder, aliases, cycle boundary, local shadow and remote reveal.
5. Offcut selection maps across listing/decompiler/references without normalization to containing
   start.
6. Large table/tree/graph remains bounded under filter, sort, expansion, cancellation and mutation.
7. Save, check-in, recovery, close and target-control commands remain distinct under auto-save.
8. Unknown modal interaction surfaces a compatibility action; no host hangs or guesses.
9. Debug historical/scratch/live coordinates and authority remain independently visible.
10. Screen-reader/keyboard traversal exposes semantic listing fields, stale/partial/error state, and
    approval details.

For VS Code, run desktop Node extension-host and browser WebWorker suites. For IntelliJ, run local
and simulated/real Remote Development placement tests. For JavaFX, run with and without the Swing
compatibility process.

## Performance and resource envelopes

Performance acceptance uses budgets tied to hardware classes, but the invariants are fixed:

- no query materializes an unbounded project tree, listing, undefined-byte sequence, table or graph;
- backpressure rejects work before queue/memory exhaustion;
- cancel acknowledgement is bounded, and an ignored cancellation reaches a documented worker
  recycle or explicit non-cancellable stage;
- decompiler, analysis, script, import, artifact and emulator pools have independent quotas;
- a slow client cannot retain unlimited events/pages/artifacts or block other clients;
- result arenas expose eviction and expire refs/cursors deterministically;
- broker and authoritative project runtime remain responsive while disposable workers are killed;
- project and recovery data survive power/process failure at every journaled phase.

Track at least p50/p95/p99 latency, queue time, peak heap/native memory, result bytes, event lag,
cancel latency, worker recycle time and leaked file/process/mount counts.

## Release gates

| Gate | Required proof |
| --- | --- |
| `G0-v1-preserved` | existing real read-only v1 suite remains green and is clearly capability-separated |
| `G1-semantic-read` | structured identity/address/scope, atomic project snapshot, sparse listing, result arena and native host stale handling |
| `G2-one-object-write` | preview/approval/revalidation, one server-owned transaction, rollback, generation/invalidation, undo and audit |
| `G3-repository` | layered tree, two identities, reconnect/resync, staged checkout/check-in/move/delete, lost-reply reconciliation |
| `G4-compound` | fixed leases, ProgramDiff coverage, archive sync/migration, partial/degraded VT outcomes, non-invertible unapply/delete, and merge conflict path including visible compatibility fallback |
| `G5-debug-read` | captured trace/time/platform/memory semantics and native historical views without live authority |
| `G6-debug-control` | explicit live authority, breakpoints, no retry after lost reply, target reconciliation and foreground indication |
| `G7-code-and-agents` | sandboxed packages, proposal path, adversarial agent suite, egress policy, secrets and complete audit |
| `G8-extension-ecosystem` | extension tiers, deterministic service manifests, restart/upgrade, opaque data, prompts and compatibility inventory |

A later gate never weakens an earlier one. In particular, enabling scripts, extensions, agents, or
live debugging cannot bypass project ownership, recovery, stale checks, resource limits, or audit.

## Ghidra upgrade gate

For each new Ghidra build:

1. build a source/Javadoc delta for every cited class in the ledger;
2. regenerate all real fixtures from a clean environment and compare semantic manifests;
3. run storage copies, never the only user project, through open/save/migration tests;
4. run old/new engine fingerprints against repository compatibility policy without concurrent
   writable access;
5. rerun extension discovery, language/cspec, analyzer defaults, decompiler, import/export, merge,
   VT and TraceRMI suites;
6. update ledger rows as confirmed, changed, retired, or newly discovered;
7. publish exact supported build/extension fingerprints and any blocked content types.

The upgrade is accepted only from executable results. “The server connected” or “the UI opened the
program” is not storage or semantic compatibility proof.
