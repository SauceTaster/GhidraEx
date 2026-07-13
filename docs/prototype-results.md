# Prototype results

Verified on macOS arm64 with Temurin 25.0.3, Gradle 9.1, and Node.js 26.

## JavaFX

- Clean build passed with eight tests.
- Launch smoke passed without startup exceptions.
- The listing exposes 100,000 synthetic instructions through a lazy, fixed-cell-size `ListView`,
  backed by bounded 256-row pages and a 12-page LRU cache.
- Project navigation, symbol search, decompiler, inspector, command palette, task cancellation, and
  split-layout persistence are implemented.

Early signal: this is the most coherent all-Java clean break. The main unanswered risk remains a
production-quality Listing/Decompiler renderer and the work required for docking and accessibility.

## Web shell plus JVM service

- Four frontend tests and eleven backend tests passed.
- The service exposes exactly 100,000 deterministic instructions through bounded, addressable
  viewport requests, including far-address retrieval.
- TypeScript checking and the production Vite build passed.
- Integration smoke verified the frontend and API, named SSE events, analysis progress, hostile
  Origin rejection, and clean shutdown.
- Browser inspection verified the rendered listing/decompiler/inspector layout, the command deck,
  streamed analysis state, and an empty warning/error console.

Early signal: this has the highest visual iteration speed and the clearest process boundary. Its
deciding cost is designing a durable, coarse-grained Workbench API and operating two processes.

## IntelliJ Platform

- Five engine/search tests passed.
- `buildPlugin` produced `build/distributions/ghidraex-intellij-workbench-0.1.0.zip`.
- `runIde` stayed up and its sandbox log confirmed that the prototype plugin loaded.
- The listing exposes 100,000 logical rows through 512-row pages and an eight-page LRU cache.
- Projects, searchable symbols, and inspector tool windows; listing/decompiler editor tabs; native
  actions; background tasks; cancellation; and notifications are implemented.

Early signal: this supplies the most workbench infrastructure immediately, but the host platform
strongly shapes the product and remains Swing-based. Optional `verifyPlugin` was not used as a gate
because it began resolving an additional large verifier IDE; compilation, tests, sandbox creation,
launch, and packaging all passed.

## VS Code / Code OSS

The fourth prototype has been reworked from a monolithic webview into a native Code OSS extension.
The implementation now targets:

- a GhidraEx Activity Bar container with Programs, Symbols, Analysis, and Agent Evidence TreeViews;
- a read-only 100,000-line virtual Listing `TextDocument` and per-function Decompiler
  `TextDocument`s in ordinary VS Code editor groups;
- native Outline symbols, CodeLens, hover, go-to-definition, find-references, semantic tokens, and
  visible-range editor decorations;
- Command Palette actions, address Input Box, symbol Quick Pick, editor/view menus, and keybindings;
- cancellable progress notifications synchronized with an Analysis TreeView, Status Bar item, and
  Log Output channel; and
- agent evidence surfaced through the Problems panel, read-only Comment threads, Code Actions, and
  the Agent Evidence TreeView.

Verification status for the native rewrite: **verified**.

- Strict TypeScript checking passed, followed by twelve unit tests after the legacy webview,
  protocol, and navigation sources were deleted.
- The desktop Node and browser WebWorker extension bundles built successfully.
- VSIX packaging produced a compact artifact whose runtime payload contains only the Node/Web
  extension bundles, Activity Bar SVG, listing grammar, license, and metadata. It contains no
  workbench HTML, CSS, or webview bundle.
- `npm run test:web` exited successfully. Its extension-host assertions cover the 100,000-line
  listing, first and final addresses, listing/decompiler language IDs and content, visible native
  editors, an Outline document symbol, definitions/references, Problems diagnostics, the evidence
  Code Action, nested TreeView-style navigation, refresh, duplicate-analysis suppression, and
  cancellation through public commands.
- Interactive browser inspection showed all four native GhidraEx trees, a native
  Listing/Decompiler editor split, Problems, Status Bar state, and CodeLens. Navigation to line
  100,000 synchronized the decompiler. Native analysis progress reached completion. The rendered
  host contained two Monaco editors, five native tree widgets, and zero `.webview` elements.
- The desktop launcher was corrected for macOS Unix-socket path limits. The isolated desktop log
  confirms extension activation and that the native workbench opened without a webview.
- Analysis cancellation semantics are covered by unit and browser-host command tests, and native
  cancel controls rendered. An interactive cancel-button click was not separately claimed as
  verified.

Early signal: this is the strongest option when desktop/web reach and the editor ecosystem matter.
VS Code supplies real editor groups, search, Outline, Problems, Comments, commands, themes,
restoration, and accessibility conventions; the prototype no longer recreates those facilities in
HTML. A focused webview remains reasonable for a future CFG/call/data-flow graph, but not for the
main workbench. The principal scaling tradeoff is that a native virtual text document is a complete
text buffer even though VS Code virtualizes its rendering. A desktop Node adapter can connect to a
local JVM; a browser WebWorker cannot launch Java, so it needs an authenticated remote engine or a
purpose-built WebAssembly backend. The VSIX targets Code OSS/VSCodium compatibility, but no local
VSCodium binary is installed, so that host is not claimed as tested.
