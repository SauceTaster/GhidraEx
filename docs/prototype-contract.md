# Prototype comparison contract

The four prototypes exercise the same analyst workflow so the comparison is about architecture,
not about four unrelated demos.

## Vertical slice

Each workbench presents the following capabilities, using the host platform's native surfaces when
they exist:

- a project/program navigator;
- symbol search and address navigation;
- a 100,000-instruction synthetic disassembly listing;
- a decompiler-style text view;
- contextual symbol/function details;
- keyboard-driven commands and navigation;
- cancellable analysis progress;
- host-native debugger lifecycle surfaces and difficult coordinate-state probes;
- scripting/REPL surfaces that distinguish bounded fixture commands from unavailable arbitrary
  runtimes and capabilities;
- backend and plugin inventory that separates detected, connected, and active providers; and
- a dense dark theme suitable for long reverse-engineering sessions.

## Engine boundary

Each implementation keeps presentation code behind the same conceptual operations:

- `openProgram(programId)`
- `listing(programId, start, count)`
- `decompile(programId, functionId)`
- `searchSymbols(programId, query, limit)`
- `startAnalysis(programId, options)`
- `cancelTask(taskId)`
- task progress events

Those names describe semantic operations, not synchronous method calls. Every retained native host
now enters listing, program, symbol, decompiler, reference/evidence, and runtime-discovery providers
through a bounded asynchronous adapter. A renderer, cell getter, Swing table model, virtual-document
formatter, TreeView callback, or action-update path may consume only a local immutable projection.

Every read request and result carries or is reduced against the complete
`{runtimeId, runtimeEpoch, programId, contentGeneration}` context. Non-listing results additionally
echo the normalized semantic query, carry a stable result ID, declare
`COMPLETE | PARTIAL | TRUNCATED`, and include bounded warnings. Latest request wins per view/scope;
superseded, cancelled, disposed, mismatched, or resync-quarantined results cannot replace the
displayed value. A coherent old value may remain visible only as `STALE` or `RESYNCING`.

The adapters bound queue depth, retained cache entries, collections, individual text fields,
warnings, and aggregate payload text before publication. Host cancellation is propagated to the
provider, but exact context/request acceptance remains mandatory because cancellation races. These
handwritten Java and TypeScript records are an executable migration seam; generated Protobuf v2
records are still the intended durable contract.

All four UI implementations use deterministic synthetic data by default. JavaFX, IntelliJ, and
VS Code / Code OSS currently have only synthetic host adapters. The web workbench is the first host
with a real mode: `./dev.sh /path/to/binary` launches one persistent, process-isolated Ghidra
session and connects the browser through the authenticated loopback gateway. Real mode never
substitutes fixture data under a real program identity.

The original one-shot exporter remains under
[`integration/ghidra`](../integration/ghidra/README.md) for deterministic compatibility snapshots.
It is complemented—not replaced—by persistent protocol v1. That session keeps one imported program
and decompiler alive at immutable revision `0` and implements:

- `program.summary` for SHA-bound identity, analysis state, address extent, and counts;
- `listing.window` for bounded forward/backward code-unit pages;
- `symbols.search` for deterministic bounded symbol pages;
- `references.list` for bounded incoming/outgoing reference pages; and
- `decompile.function` when the worker advertises an available decompiler.

The browser replays `session.ready`, negotiates `session.hello`, and retains the exact
`{programId, revision}` on every read. Addresses and cursors are opaque strings. The gateway token
stays in the launcher/Vite server boundary rather than browser code, URLs, or workspace settings.
Real-mode analysis start, debugger/target control, writes, scripts/REPL, and engine-plugin activation
remain capability-disabled because v1 advertises none of those authorities.

No presentation layer receives a `Program`, `Listing`, `Function`, Swing component, Ghidra
classloader identity, or implicit process authority. A configured path means “distribution
detected”; only an explicit successful transport handshake may mean “connected.”

### Retained-host real-adapter gate

A JavaFX, IntelliJ, or VS Code adapter is not considered real merely because its synthetic loader
can be replaced. It must pass all of these gates before the UI may label its data connected:

1. Establish and validate a runtime/program snapshot before constructing resource-bound editors or
   documents; fixture bootstrap IDs may never survive that transition.
2. Negotiate advertised methods, limits, address semantics, cancellation, and event replay. A
   missing capability disables its command instead of routing to a similarly named fixture action.
3. Decode within transport byte/depth/string limits, then map into the host's stricter typed
   field/collection/aggregate limits. Host validation alone is too late to prevent decoder memory
   pressure.
4. Echo exact runtime/program/generation and normalized query identity on every result. Cache keys
   must use unambiguous structured tuples rather than display strings or delimiter concatenation.
5. Propagate supersession, view disposal, resync, deadline, and user cancellation to the provider;
   still reject a late completion deterministically and release any server result lease.
6. On an event gap or transport fault, retain only coherent real data marked stale/resyncing. Never
   place synthetic rows under the real context or restart a worker invisibly.
7. Close the session with a bounded owner lease and prove child-process, port, credential,
   temporary-project, cache, and native-resource cleanup.

The implemented synthetic adapters and shared state suites prove the host side of items 3–6. The
JSON v1 gateway/web integration proves a limited real read-only form of items 1–3, 5, and 7. A v2
generated-Protobuf adapter must satisfy the complete gate for each retained host.

The VS Code/Code OSS experiment is deliberately a native extension rather than a web application
inside a `WebviewPanel`. Its Programs, Symbols, Analysis, Agent Evidence, Debug Sessions,
Scripting & REPL, and Capabilities & Plugins navigators are Activity Bar TreeViews. The listing and
decompiler are read-only virtual `TextDocument`s, so VS Code owns their
editor tabs, splits, search, selection, keyboard behavior, accessibility, and restoration. Native
document providers supply Outline symbols, hover, definitions, references, CodeLens, semantic
highlighting, and visible-range decorations. Problems diagnostics, read-only Comments, Quick Pick,
progress notifications, the Output panel, and the Status Bar carry the remaining workflow state.
An inline Debug Adapter Protocol implementation exercises native session, breakpoint, frame,
scope, register, step, pause, continue, and evaluation flows against a labeled synthetic trace. A
native pseudoterminal and `.gxscript` documents exercise bounded commands. Templates, backend
settings, and plugin reports expose the missing states; Python/Java execution, local/remote
transports, and Ghidra plugin lifecycle remain unavailable until real providers implement them.

The extension has separate desktop Node and browser WebWorker entry bundles over the same
toolkit-neutral engine facade. Stable virtual-document URIs include runtime, program, content
generation, address-space, and bounded-window identity, preventing old documents from being
mistaken for current analysis. The 100,000-row fixture remains navigable, but a listing document
projects at most 4,096 semantic rows and yields between smaller engine pages while constructing the
text. An explicit line map translates editor lines to program rows and semantic locations; language
providers do not treat line number as program identity. Production adapters may choose still
smaller function-, block-, or segment-scoped documents without changing that state contract.

The desktop `local` and browser-compatible `remote` selections are inspectable connection
contracts, not real providers. When unavailable, the extension reports the reason and continues on
an explicitly labeled synthetic read-only fallback. A browser WebWorker cannot launch the local
gateway; it will require an authenticated remote HTTPS service or a purpose-built WebAssembly
engine. This limitation applies to the VS Code browser extension host, not the standalone web
workbench, whose local Vite launcher safely proxies to its leased loopback gateway.

Webviews are outside the fourth prototype's main workbench contract. A future control-flow,
call-graph, or data-flow canvas may justify a focused webview because those spatial interactions do
not map well to a text editor or TreeView. Such a view should remain a projection of extension-host
state rather than becoming a second application shell.

## Comparison gates

The prototypes should be judged using the same gates:

1. Smooth navigation through at least 100,000 synthetic instructions.
2. Search result latency and cancellation behavior.
3. Keyboard navigation, focus, scaling, and accessibility semantics.
4. Dock/split/window behavior and layout restoration.
5. Startup time, resident memory, packaged size, and platform-specific build work.
6. Clarity of the engine boundary and effort required to add a provider or command.
7. Fidelity to the host's native navigation, diagnostics, progress, theming, and restoration
   conventions.
8. Provider truth: detected, starting, ready, stale, and unavailable states must remain distinct,
   and a failed real provider must never silently become a fixture provider.
9. Protocol fidelity for real hosts: opaque addresses, context/revision checks, advertised
   capabilities, bounded paging, event gaps, cancellation, and owned cleanup.
