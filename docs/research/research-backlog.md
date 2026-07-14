# Research backlog

This backlog tracks uncertainty that could still change process boundaries, protocol identity,
authority, recovery, or host feasibility. It is ordered by cost of discovering the answer late.

`Research` means inspect contract, source and tests; `Proof` means write a minimal real Ghidra
fixture or failure injector; `Design` means turn confirmed behavior into a protocol/host decision.

## P0 — resolve before accepting protocol v2

| Workstream | Questions | Required output / exit criterion |
| --- | --- | --- |
| Repository authorization and server failure | How are ACL changes, anonymous access, admin actions, checkout ownership, stale sessions, server clocks, and server-side backup/restore exposed? Which calls are retryable or reconcileable? | Permission/identity matrix, operation descriptors, lost-reply tests, least-privilege deployment guide |
| Merge resolver inventory | Enumerate every built-in `MergeResolver`, conflict type, option, modal UI, cancellation point, progress state, result provenance and extension hook. Which can be made semantic and which require Swing? | Version-pinned inventory, conflict DTO sketches, fixture per resolver, measured legacy coverage percentage |
| Save/recovery/storage crash model | Map domain-object save, recovery snapshots, database handles, `.keep` files, undo checkpoints, project close, disk-full and process/power loss. Identify atomic rename/fsync assumptions. | Phase diagram and crash harness proving no silent recovery deletion or false saved state |
| Content-handler matrix | For every built-in domain-object content type, document read-only/historical/share/checkout/merge/reset/link/recovery/upgrade capabilities and compound dependencies. | Machine-readable capability registry and `SafeOpen` fixtures; unknown types fail closed |
| Import/filesystem isolation | Audit every built-in loader/filesystem probe for blocking, global state, paths, passwords, nested mounts, decompression, native parsing and output multiplicity. | Disposable import-worker design, opaque artifact/mount handles, quotas, all-candidate `ImportProbe` fixture |
| Option adapter inventory | Enumerate loader/exporter/analyzer/parser option value classes, validation, defaults, Swing editors and global side effects. | Versioned typed adapter registry; each built-in option is portable, `UI_ONLY`, or unsupported—never reflected generically |
| Datatype transaction/archives | Finish source archive/universal ID/conflict/dependency/deletion/upgrade analysis, including built-in/file/project managers and concurrent Structure Editor behavior. | Durable type refs, conflict graph, one-object/compound atomicity map, archive upgrade/recovery tests |
| Analysis ownership | Trace all analysis entry points, static per-program state, tool ownership, option mutation, event ordering, cancellation/rollback, headless differences, and analyzer extension behavior. | One authoritative queue per program, deterministic resolved run manifest, quiescence definition and test |
| Debug target authority | Inventory TraceRMI methods, target method schemas, launchers, connectors, attach, terminal I/O, kill/detach, register/memory writes, focus and secret flows. | Per-operation permission/idempotency/outcome descriptors and a no-blind-retry target harness |
| Emulation/userop/inject model | Map inject ownership/precedence, execution/watch breakpoints, custom userops, arithmetic domains, static/live fallbacks, step granularity and cache keys. | Deterministic emulator preflight manifest, injection registry, fidelity labels and conflict fixtures |
| Protocol resource lifetimes | Prove leases, result arenas, cursor expiry, event resumption, reconnect/full-snapshot replacement, artifact GC, client disconnect and broker/project restart behavior. | Executable first v2 vertical slice with zero leaked consumers/processes/files/mounts |
| Secret and tenant isolation | Trace repository, encrypted container, symbol server, debugger, model and extension secrets through caches, logs, preferences, dialogs, child processes and crash dumps. | Secret-handle API, redaction tests, per-principal worker/cache boundaries and threat-model review |

## P1 — resolve before feature-complete native ports

| Workstream | Questions | Required output / exit criterion |
| --- | --- | --- |
| Search semantics | Bytes, memory, instruction patterns, text, scalar, register, references, symbols and decompiler searches have different scope, alignment, cancellation and live/stale rules. | Typed search kinds, shared paged result resource, coverage/completeness fixtures |
| Function/signature editing | Inventory stack purge, calling convention, custom storage, auto/forced-indirect params, thunks, external functions, local variables and HighFunction write helpers. | Specialized proposal/impact DTOs and rollback/stale tests across several architectures |
| Memory map and patch editing | Audit block create/move/split/join/delete, mapped blocks, overlay lifecycle, file-byte provenance, context, relocations and reanalysis effects. | Maintenance versus ordinary edit classification and full patch impact preview fixture |
| Source/debug artifacts | Complete PDB, DWARF, dSYM, PE/ELF debuglink/build-ID, source maps, path transforms, symbol-server caches, trust and reproducibility. | Egress-controlled artifact service with candidate/provenance/trust/size policy |
| Parser/demangler ecosystem | Beyond C parsing, inspect demanglers, Sleigh compilation, assembler, DWARF expression parsing, format parsers and language-specific helpers for globals, code execution and partial commits. | Worker-classification matrix and scratch-then-apply policy for mutating parsers |
| BSim and external databases | Provider versions/schema compatibility, ingestion/mutation/admin authority, cancellation, credentials, SQL/native resources and result provenance. | Separate read/ingest/admin APIs, leased connections, deadline cancellation and compatibility fixture |
| Debug stack/register/module views | Unwinder confidence/partial state, ISA/platform mapping, register banks, frames, modules/sections, symbolization and historical/live consistency. | Provenance-bearing stack/frame DTO and cross-platform trace fixtures |
| Breakpoint/watchpoint parity | Compare software/hardware/access/trace/emulated breakpoints, conditions, commands, locations, pending states and multi-target fan-out. | Capability/fidelity matrix and native debugger UX acceptance tests |
| Extension service surface | Inventory Ghidra services, plugin lifecycle, duplicate providers, events/options/help, arbitrary classes/property maps, actions/dialogs and class discovery. | Extension manifest/schema, deterministic provider selection, restart/upgrade and tier compatibility suite |
| Swing compatibility measurement | Identify every workflow that still produces top-level windows/modal dialogs or requires Docking actions/components. | Automated AWT-window detector, typed launch/outcome wrappers, published compatibility inventory |
| Native listing/editor fidelity | Field semantics, wrapping, headers, cursor/selection/highlights, drag/drop, hover, keyboard, IME, bidi, copy, printing and huge sparse layouts. | Cross-host interaction spec, semantic accessibility tree and performance gates |
| Workspace/layout migration | Docking topology, detached windows, pinned/snapshot documents, connected views, history, keymaps, options and cross-device/host migration. | Host-neutral workspace schema plus host-specific extension state and atomic migration tests |

## P2 — resolve before broad ecosystem claims

| Workstream | Questions | Required output / exit criterion |
| --- | --- | --- |
| UI accessibility and internationalization | Screen readers, keyboard-only use, contrast, font scaling, high DPI, IME, RTL/bidi and localization across semantic custom surfaces | WCAG/platform checklist, automated accessibility scans and manual assistive-technology runs |
| Printing/reporting/export fidelity | Which views/results can be printed or reported, what state/provenance is embedded, and how are executable/raw outputs handled? | Artifact-set format, redaction/egress policy, atomic export and reproducibility tests |
| Collaboration presence | Multi-client cursors/selections/comments/locks, optimistic edits, approvals and audit visibility without leaking private workspace state | Separate presence protocol, privacy model and concurrent-client fixture |
| Host plugin SDKs | Stable portable semantic view/renderer/action APIs versus JavaFX-, JetBrains-, and VS Code-specific extension points | Versioned host SDK proposal and two non-core example plugins per tier |
| Packaging/updating/signing | Standalone installers, JetBrains/VSIX distribution, engine/JDK acquisition, signatures, SBOM, extension provenance and rollback | Reproducible signed package pipeline and offline/enterprise install guide |
| Upstream synchronization | Patch policy, tagged-build rebase, module overlays, contribution strategy, licensing/notices and unsupported fork surface | Written upstream policy, patch-size budget and automated source/API delta report |
| Telemetry/privacy | What performance/crash metrics are useful, which program/project/target data must never leave, and how consent/redaction works | Default-off or privacy-reviewed schema with local inspection/deletion and no binary content |
| Disaster recovery | Broker audit/journal/workspace backup, repository backup, local project/recovery, artifacts and encryption keys | Restore drills, retention policy and proof that `.gar` is not misrepresented as server backup |

## Targeted source audits queued

These are deliberately narrower than the workstreams and can be parallelized without conflicting
design edits:

1. all implementations of `ContentHandler`, `MergeResolver`, `Loader`, `GFileSystemFactory`,
   `Exporter`, `Analyzer`, `DebuggerPlatformMapper`, and target method invocation;
2. every `startTransaction`/`endTransaction` pattern that commits from `finally` or spans several
   domain objects;
3. all calls that pass `okToRecover=false` or open transitive domain-object dependencies;
4. static/singleton fields under project, repository, loader/filesystem, parser, analysis,
   decompiler, debugger, preferences, theme, clipboard, extension and credential packages;
5. APIs that accept `File`, `Path`, `URL`, `URI`, `FSRL`, command lines, class names, scripts or
   arbitrary `Saveable` values across a prospective remote boundary;
6. loops/iterators/tasks without `TaskMonitor`, collection/result caps, or deterministic ordering;
7. exceptions/cancellation converted to booleans, warnings, empty collections or not-found;
8. caches whose keys omit Ghidra build, extension, language/compiler, options, principal,
   generation, platform mapping, transform version or source provenance;
9. Swing dialogs/components created below ostensibly headless service APIs;
10. repository and live-target methods retried after a transport fault.

## Finding intake template

Use this template for additions to the ledger:

```markdown
### Candidate ID — short title

- Ghidra build and source/API link:
- Contract, implementation detail, or inference:
- Trigger and observed state:
- Data/authority/resource consequence:
- Affected runtime and host surfaces:
- Candidate containment:
- Smallest falsifying conformance test:
- Open questions and upgrade sensitivity:
```

If a finding changes process isolation, identity, transaction/atomicity, retry, authority, or
recovery, update the relevant architecture document and proposed ADR in the same change. If it only
adds a view behavior, link it from the host and conformance documents without inflating the core
protocol.
