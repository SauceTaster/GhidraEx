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
- cancellable analysis progress; and
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

The first pass uses deterministic synthetic data. The boundary is intentionally coarse enough to
be implemented in-process by JavaFX/IntelliJ, transported over HTTP and events by the web shell,
or brokered through a VS Code extension host. Real Ghidra integration can then implement the same
facade without coupling the workbenches to `Program`, `Listing`, `Function`, or Swing objects.

The VS Code/Code OSS experiment is deliberately a native extension rather than a web application
inside a `WebviewPanel`. Its project, symbol, analysis, and evidence navigators are Activity Bar
TreeViews. The listing and decompiler are read-only virtual `TextDocument`s, so VS Code owns their
editor tabs, splits, search, selection, keyboard behavior, accessibility, and restoration. Native
document providers supply Outline symbols, hover, definitions, references, CodeLens, semantic
highlighting, and visible-range decorations. Problems diagnostics, read-only Comments, Quick Pick,
progress notifications, the Output panel, and the Status Bar carry the remaining workflow state.

The extension has separate desktop Node and browser WebWorker entry bundles over the same
toolkit-neutral engine facade. Stable virtual-document URIs include the program identity and
revision, preventing old documents from being mistaken for current analysis. The 100,000-line
prototype document is materialized as text in page-sized chunks; VS Code virtualizes its rendering,
not its backing text buffer. A production adapter should therefore expose function- or
segment-sized documents for substantially larger programs.

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
