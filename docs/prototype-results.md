# Prototype results

Verified on macOS arm64 with Temurin 25.0.3, Gradle 9.1, Node.js 26, Ghidra 12.1.2,
and Temurin 21.0.11+10 for the isolated Ghidra process.

All four workbenches still open deterministic fixture programs by default. The web workbench is now
the first host with an additional real adapter; JavaFX, IntelliJ, and VS Code / Code OSS remain
synthetic host adapters. “Ghidra detected” is intentionally not the same as “engine ready,” and a
failed real connection never inherits a fixture program's data or capabilities.

## Cross-host semantic view proof

- The shared Java reducer/controller suite passes 44 tests. In addition to the semantic listing
  reducer, it now contains a typed contextual slot/controller for program, symbol, decompiler,
  reference, inspector, and runtime reads. It proves latest-request-wins, exact context and query
  echoes, bounded defensive publication, partial/truncated state, resync quarantine, identity
  replacement, worker/dispatcher rejection, cancellation, and queued/late-work disposal.
- JavaFX and IntelliJ consume the same included build. VS Code mirrors the reducer in a
  toolkit-neutral TypeScript core with 21 parity/adversarial tests. The long-term v2 boundary is
  generated Protobuf; these handwritten types are an executable schema sketch, not a second
  permanent wire model.
- All three retained hosts now project bounded immutable listing windows into native controls and
  route their user-facing read workflows through bounded asynchronous adapters. No `ListCell`,
  Swing table getter, VS Code document/language provider, TreeView renderer, or action-update path
  enters the engine. Result collections, fields, warnings, caches, and aggregate payloads are
  bounded before publication.
- The adversarial pass found a concrete lifecycle bug: JavaFX cleared shared state on a validated
  runtime/program replacement but retained its old local rows. The projection now clears rows and
  selection atomically when the accepted snapshot has no displayed result.

Proof verdict: the generation-aware semantic state spine is viable for bounded read-only listing,
program tree, symbol, decompiler, inspector, xref/evidence, runtime inventory, and safe REPL-query
projections. It is not proof for graph/memory scale, debugger time/control authority, arbitrary
scripts, engine plugins, transactions, or mutation recovery; those need distinct typed coordinates,
capabilities, and failure suites.

### Retained-host usability matrix

| Gate | JavaFX | IntelliJ | VS Code / Code OSS |
| --- | --- | --- | --- |
| Native shell | JavaFX stage, tabs, menus, command palette, persisted split layout | IDE project, editors, actions, tool windows, keymap, notifications | Activity Bar TreeViews, native editors/language APIs, Problems, Comments, Status Bar, Quick Pick |
| Listing | 256-row immutable semantic window | 512-row immutable semantic window | 4,096-row document shard assembled from 256-row pages |
| Program/symbol/decompiler/xrefs | bounded async panes with local snapshots | dedicated latest-only projections; native xref navigation | shared async service across documents, providers, trees, evidence, commands, and REPL |
| Race/failure state | latest context/query wins; partial/stale/error/resync visible | same shared Java slot/controller through one EDT gateway | exact context/scope acceptance, abort propagation, partial/truncated warnings, native status projections |
| Analysis | worker launch, progress, cancellation, generation refresh | background task, request/context validation, symbol/read refresh | cancellable native progress and Analysis TreeView |
| Debug/scripts/plugins | native fixture labs; authority clearly gated | native fixture debugger and `ConsoleView`; authority clearly gated | real host DAP/pseudoterminal surfaces over explicitly synthetic bounded models |
| Automated gate | 34 host tests + clean distribution build | 34 host tests + plugin/package verification + clean sandbox activation | 80 host tests + browser-host smoke + 11-file VSIX |
| Real Ghidra data | not connected | detected but not connected | local/remote settings contract, not connected |

“Usable” here means the complete deterministic read-only vertical slice launches, navigates, fails,
cancels, and disposes coherently in each native host. It does **not** mean the three retained hosts
have a real v1/v2 Ghidra adapter; the matrix keeps that separate so fixture polish cannot be mistaken
for backend completion.

## JavaFX

- Clean build passes 34 tests; terminal launch smoke reaches the running application without a
  startup exception.
- The 100,000-row fixture is navigable through one bounded 256-row semantic `ListView` projection.
  A bounded worker pool loads immutable instruction/data/gap rows; cells only read local state, and
  one FX dispatcher applies accepted `LOADING`, `CURRENT`, `STALE`, `PARTIAL`, `FAILED`, or
  `RESYNCING` snapshots.
- Listing, Decompiler, Debugger, Scripting, and Extensions tabs are available from native JavaFX
  controls, commands, and nested menus.
- Symbol type-ahead, decompilation, xrefs, listing requests, and analysis launch/cancel enter the
  engine only on bounded workers. Each pane renders immutable snapshots with visible loading,
  current, stale, partial/truncated, failed, and resync states. Rapid replacement purges cancelled
  queued tasks rather than consuming the shared queue.
- The debugger lab covers current/past snapshots, stale-memory labeling, emulator forking, step
  state, registers, call stack, watches, and write isolation.
- The script lab separates fixture reads, mutation approval, and blocked process/network access.
  Backend detection and extension inventory never imply a connected script host or Trace/RMI
  target.

Early signal: this remains the most coherent all-Java clean break. Its largest product work is now
docking/accessibility depth and a real adapter for the shared engine gateway; the interactive
fixture read paths no longer need another scheduling migration.

## Web workbench: synthetic JVM or real Ghidra

- The focused frontend suite passes 13 Vitest tests across model, advanced-state, and protocol
  client files. Strict TypeScript checking, the production Vite build, and `bash -n dev.sh` pass.
- Synthetic mode still exposes exactly 100,000 deterministic instructions through bounded
  addressable viewport requests, including far-address retrieval, while analysis updates stream
  over SSE.
- Code, Debugger, Scripts, and Extensions workspaces cover historical trace state, emulator forks,
  capability-gated script plans, runtime detection, and plugin inventory.
- Real mode launches with `./dev.sh /path/to/binary`. The launcher starts a parent-leased gateway,
  reads its owner-only descriptor, and keeps the bearer credential in Vite's server-side proxy.
  The browser bootstraps from replayed `session.ready`, negotiates protocol v1, and consumes real
  summary, listing, symbol, reference, and decompiler results with no synthetic fallback.
- Browser QA against local Ghidra 12.1.2 loaded a Mach-O AArch64 fixture and rendered its SHA-bound
  target, 2 functions, 48 instructions, 6 symbols, real code-unit bytes, opaque `ram:*` addresses,
  `_rotate_mix` decompilation, and cross-reference counts. Selecting `_main` synchronized the
  listing at `ram:100000328`, inspector, references, and real decompilation.
- Unsupported real analysis, debugging, scripting, mutation, and extension controls stayed
  capability-disabled. The browser warning/error console remained empty. Exiting the launcher
  closed the gateway port and removed its runtime directory and temporary Ghidra project; the QA
  harness removed its native fixture.
- This QA found and fixed a browser-only unbound-`fetch` failure, then repeated the real workflow
  successfully. Synthetic service port `18787` remains configurable with
  `GHIDRAEX_SERVICE_PORT`.

Early signal: this has the fastest visual iteration, the clearest process boundary, and the first
end-to-end proof of a real read-only host. A remotely deployable version still needs user
authentication, authorization, TLS, quotas, and multi-user program isolation beyond the local
bearer gateway.

## IntelliJ Platform

- 34 tests pass. `buildPlugin`, `verifyPluginProjectConfiguration`, and
  `verifyPluginStructure` pass and produce
  `build/distributions/ghidraex-intellij-workbench-<version>.zip`.
- A live `runIde` smoke opens the disposable demo project rather than the project selector, reaches
  `GHIDRAEX_DEMO_READY`, navigates the 100,000-row fixture through a bounded 512-row native editor,
  and shows four workbench tool windows with no plugin exception.
- The post-migration sandbox smoke caught a plugin-attributed `IncorrectOperationException` when an
  accepted async decompiler result tried to mutate a read-only `LightVirtualFile`. The projection
  now opens one EDT write-action window, restores read-only immediately, and a fresh sandbox launch
  reaches readiness without the exception.
- `JBTable` getters now read immutable local semantic rows only. A pooled worker loads windows and
  one EDT gateway publishes reducer-approved state; editor identity contains runtime/program
  context rather than relying on display filename or row ordinal.
- Programs, server-side symbol filtering, selected-symbol decompilation, inspector details, bounded
  xrefs, and runtime/capability/plugin discovery each own a one-running/one-queued latest-only
  worker. Their result records enforce nested per-field and aggregate budgets before the EDT sees
  them; xref rows navigate back into the native listing editor.
- The native RE Lab contains Debugger, Scripts & REPL, and Runtime & Plugins tabs. It uses IntelliJ
  actions and a real `ConsoleView`, while its debugger and command results remain labeled fixtures.
- Backend discovery distinguishes the active `SyntheticAnalysisEngine` from a detected Ghidra
  distribution and now finds repository-local `.toolchains/ghidra_*_PUBLIC` installations without
  requiring an exported environment variable.

Early signal: IntelliJ supplies extensive workbench infrastructure immediately, but remains
Swing-based and strongly shapes product packaging. Binary compatibility across IDE releases and a
real gateway adapter remain unverified; arbitrary Python/Java and live debugging stay blocked, but
the retained read paths now share the asynchronous projection and context-acceptance treatment.

## VS Code / Code OSS

- Strict TypeScript checking and 80 unit tests pass, including the semantic reducer plus async read
  service, native document, TreeView, REPL, scripting, and DAP projection suites. Desktop
  Node and browser WebWorker bundles
  build from the same webview-free extension source.
- Seven native Activity Bar TreeViews cover Programs, Symbols, Analysis, Agent Evidence, Debug
  Sessions, Scripting & REPL, and Capabilities & Plugins. Listing and Decompiler remain ordinary
  virtual `TextDocument`s in editor groups.
- The inline DAP adapter exercises VS Code's native Run and Debug UI: session, call stack, scopes,
  registers, listing breakpoints, stepping, pause/continue, Debug Console evaluation, and
  debug-to-listing synchronization. Each stop asynchronously prefetches one bounded contextual
  program/decompiler/caller/xref snapshot; DAP render requests consume that immutable projection
  and expose loading/partial/error state. It explicitly identifies the target as synthetic.
- A native pseudoterminal provides a bounded `gx>` REPL; editable `.gxscript` documents run the
  same command model. PyGhidra and Java GhidraScript templates are created as normal editors but
  refuse execution until a real backend exists.
- Browser extension-host smoke covers navigation across the 100,000-row fixture through bounded
  listing documents, language features, Problems and evidence actions, analysis/cancellation, a
  DAP breakpoint-to-listing flow, script execution, and terminal creation.
- Each listing document contains at most 4,096 semantic rows, is assembled in smaller cancellable
  pages, and retains an explicit editor-line-to-semantic-location map. Eight listing projections
  and 64 decompilations are retained at most; runtime/program/generation/address-space/window
  identity is encoded in the virtual URI.
- Documents, language features, programs/symbol/evidence trees, Quick Picks, controller refresh,
  and bounded REPL commands share `NativeReadService`. It uses collision-free tuple cache keys,
  per-kind LRUs, non-regressive validated snapshots, result completeness/warnings, and internal
  transport abort on supersession, invalidation, resync, and disposal.
- VSIX packaging produces an 11-file, 93,660-byte development artifact containing the desktop/browser
  bundles, listing and script grammars, language configuration, icon, license, and metadata—no
  workbench HTML/CSS or webview bundle.

Early signal: this is the strongest option when desktop/web reach and editor ecosystem integration
matter. The bounded native-document workaround is viable, but VS Code still has no true viewport
callback; production adapters should choose function-, block-, or segment-sized scopes and test
much larger programs. VSCodium compatibility is targeted but remains untested locally. Local
JVM/remote HTTPS transports, event-gap recovery from a real stream, exact Protobuf `int64` handling,
Trace/RMI, real Python/Java execution, and Ghidra extension lifecycle are still contracts rather
than implementations.

## Real Ghidra backend

- Both real paths discover official installations, keep the UI JDK 25 separate from Ghidra's JDK
  21, and invoke `support/analyzeHeadless` in a supervised process group.
- The batch exporter remains a deterministic compatibility path. It validates input SHA-256,
  schema and collection bounds, analysis/process timeouts, process-tree cleanup, and byte-identical
  normalized snapshots across fresh imports.
- The persistent worker retains one analyzed program and decompiler at immutable revision `0`.
  Nonce-framed stdio and strict protocol v1 provide summary, bounded listing pages, symbol pages,
  references, decompilation, liveness, cancellation, and graceful close. A timed-out request gets
  one cancellation grace period; an ignored cancel faults concurrent callers, force-terminates the
  process tree, and removes the temporary project.
- The loopback gateway adds a 256-bit bearer token, exact Host/Origin checks, parent-process
  identity lease, private descriptor, bounded HTTP/NDJSON transport, late-client ready replay, and
  owned engine/project cleanup.
- Real integration exercises the worker and gateway against the native fixture. The web result
  above proves the same gateway contract in a browser host rather than only through a Python test
  client.

This is a real, interactive, read-only boundary—not a write/debug/script/plugin backend. Program
mutations and undo, live or trace debugging, arbitrary GhidraScript/Python execution, REPL state,
and engine-extension lifecycle still require their separate authority and recovery designs.
