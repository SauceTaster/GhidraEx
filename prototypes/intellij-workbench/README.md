# IntelliJ Workbench Prototype

This is a runnable IntelliJ Platform **plugin prototype**, not a repackaged custom IDE distribution. That is deliberate: it lets us test the parts of the IntelliJ option that matter—tool windows, editors, actions, keymaps, background work, layout persistence, and notifications—without first taking ownership of product branding, updates, and distribution.

The target is IntelliJ IDEA Community 2025.1.5 (platform branch `251`, Java 21). Development and Gradle can run on JDK 25; Java sources are compiled with `--release 21`, matching the target platform runtime.

## What is implemented

- **RE Projects** tool window with a synthetic `orbit-controller.bin` program.
- **RE Symbols** sortable table whose selection drives workbench context, with live filtering across symbol name, address, kind, and signature.
- **RE Inspector** context-sensitive symbol and analysis details plus a bounded xref table whose
  rows navigate back to the listing.
- A native center-editor listing over a **bounded 512-row semantic window**. `JBTable` getters read immutable local rows only; a pooled worker loads the next window, the shared view-state reducer rejects superseded/context-mismatched results, and one EDT gateway atomically applies accepted `LOADING`, `CURRENT`, `STALE`, `PARTIAL`, `FAILED`, or `RESYNCING` state.
- A native read-only editor tab for C-like decompilation, backed by a `LightVirtualFile`.
- Programs, symbols, selected-symbol decompilation, inspector details, xrefs, and runtime/plugin
  discovery use dedicated one-running/one-queued latest-only workers. Results are exact-context and
  query qualified, defensively copied, field/aggregate bounded, and visibly expose
  `LOADING`, `CURRENT`, `STALE`, `PARTIAL`, `FAILED`, or `RESYNCING` on native surfaces.
- Platform actions available through **Tools → Reverse Engineering**, **Find Action**, and Search Everywhere's Actions results.
- `Ctrl+Alt+G` opens the program; `Ctrl+Alt+Shift+G` starts analysis. Shortcuts can be remapped through the normal IntelliJ keymap UI.
- Cancellable, determinate background analysis in the IDE status bar, followed by a platform notification and live symbol-table refresh.
- **RE Lab → Debugger** is a native debugger-state workbench with actions for start, resume,
  pause, step into/over/out, stop, and symbol breakpoints. It exposes process/thread/frame,
  register, breakpoint, hit-count, and event-log states. The adapter is visibly labeled synthetic;
  no target or Ghidra trace is implied.
- **RE Lab → Scripts & REPL** uses IntelliJ's `ConsoleView`, a script catalog, prompt history,
  typed output/error results, and listing navigation. Its Python/PyGhidra and Java/GhidraScript
  modes exercise a safe bridge contract (`program()`, `symbol()`, `goTo()`, `debugger()`, and
  `backend()`). Arbitrary code fails visibly until a real language host is attached.
- **RE Lab → Runtime & Plugins** shows the active provider, Ghidra installation discovery,
  headless launcher, capability matrix, and IntelliJ/Ghidra extension lifecycle. A detected Ghidra
  distribution is reported as **available, not connected**, while `SyntheticAnalysisEngine`
  remains the active provider.
- A toolkit-neutral engine interface with deterministic tests. No IntelliJ or Swing type crosses that boundary.

Double-click a project to open it. Select symbols to update the Inspector; double-click a symbol to navigate to its address in the listing.

## Run it

From this directory:

```bash
export JAVA_HOME="/path/to/jdk-25"
./gradlew test buildPlugin
./gradlew runIde
```

Run the deterministic IntelliJ listing-spine proof alone with:

```bash
JAVA_HOME="$(cd ../.. && pwd)/.toolchains/jdk-25/Contents/Home" \
  ./gradlew test --tests dev.ghidraex.intellij.editor.IntelliJListingProjectionTest
```

If you keep an optional repository-local toolchain, set `JAVA_HOME` to
`$(cd ../.. && pwd)/.toolchains/jdk-25/Contents/Home` instead.

The standard `runIde` task does not stop at IntelliJ's project selector. It copies the bundled
[`demo-project`](demo-project) into the ignored `build/runIde-demo-project` directory, opens that
disposable copy, then automatically opens the virtualized listing and decompiler and shows the RE
Projects, Symbols, and Inspector tool windows. No project creation or menu action is required.
The bottom RE Lab also opens on its Debugger content; Scripts & REPL and Runtime & Plugins are
normal IntelliJ tool-window tabs.

The startup behavior is marker-scoped: the plugin only auto-opens the workbench when the project
contains `.ghidraex-demo`. Opening an ordinary project with the plugin installed does not hijack its
editor layout; the normal actions remain available there.

`runIde` downloads/uses the declared IntelliJ Platform distribution and launches a separate sandbox; it does not modify your installed IDE.

## Architecture

```text
IntelliJ actions / tool windows / editor tabs
                    │
             WorkbenchSession
                    │
 contextual slots + toolkit-neutral AnalysisEngine
                    │
       SyntheticAnalysisEngine (now)
                    │
       persistent Ghidra sidecar (next)
```

The engine contract is reached only through worker loaders in the project-scoped
`WorkbenchSession`. Listing uses a dedicated projection; the other read workflows use
`IntelliJContextualReadProjection` and `LatestOnlyExecutorService`. Painting, rendering, selection,
table getters, startup callbacks, and action updates consume local snapshots only. Every request
carries runtime/program/content-generation context plus its typed query; only the latest exact
result reaches Swing through one EDT gateway. The repository's
[`analyzeHeadless` bridge](../../integration/ghidra/README.md) already proves isolated one-shot
Ghidra import, analysis, and facts export. The next adapter should evolve that boundary into a
persistent RPC sidecar; Ghidra classes and mutable domain objects should not enter IntelliJ's
classloader.

The current worker source still adapts `SyntheticAnalysisEngine`; the proof validates host
threading, freshness, disposal, and identity boundaries, not a connected Ghidra program or transport.
Debugger, script-console, backend discovery, and plugin lifecycle state are also toolkit-neutral
models. Native actions and panels observe them through the project-scoped `WorkbenchSession`; this
keeps the state machines testable without booting an IDE.

## Point it at a real Ghidra distribution

The prototype can discover a local Ghidra installation without loading Ghidra classes into the
IntelliJ process. Set one of the following before `runIde` (the system property takes precedence):

```bash
export GHIDRA_HOME="/path/to/ghidra"
# or: export GHIDRA_INSTALL_DIR="/path/to/ghidra"
# or: add -Dghidraex.ghidra.home=/path/to/ghidra to the sandbox IDE VM options
```

Repository-local `.toolchains/ghidra_*_PUBLIC` installs and an `analyzeHeadless` launcher on `PATH`
are also discovered automatically. Use **Tools → Reverse Engineering → Backend & Plugins → Refresh Ghidra Backend Discovery**.
Discovery verifies `support/analyzeHeadless` (or its Windows batch launcher) and
`Ghidra/application.properties`, then reports the version and capability state in RE Lab.

This is a readiness probe, not an implicit connection. The safe progression for a real adapter is:

1. Connect the implemented batch import/analysis/facts bridge to `AnalysisEngine` reads.
2. Evolve it into a persistent process with paging, decompiler, cancellation, and disposal.
3. Add explicit program transaction lifecycles and attach PyGhidra/GhidraScript and Debugger
   Trace/RMI as separately negotiated capabilities.
4. Discover third-party Ghidra extensions inside that Ghidra host, never by adding arbitrary plugin
   JARs to IntelliJ's classloader.

The Runtime & Plugins matrix keeps partial progress testable: installation detected, process
started, project imported, program open, transaction service ready, script host ready, and trace
adapter ready should remain separate states rather than one ambiguous “connected” flag.

## Why a plugin before a custom product

A custom IntelliJ-based product is possible, but it adds a second decision set: platform source builds, product modules, application info/branding, bundled plugin policy, patch/update feeds, installers, signing, and JetBrains-platform license/notice work. None of that changes whether the workbench interaction model succeeds.

If this slice performs well, promote it in two steps:

1. Replace `SyntheticAnalysisEngine` with the process-isolated sidecar adapter and exercise large
   listings, decompiler updates, graph views, cancellation, and transaction semantics.
2. Decide whether to ship the plugin on a supported IntelliJ distribution or maintain a branded custom product. The UI code and engine boundary remain applicable to either route.

## Intentional prototype limits

- Listing rows are structured, bounded, and asynchronously projected, but the synthetic source emits
  instruction rows only and does not yet provide data/gap fixtures in the running UI, per-token
  styling, keyboard field navigation, or inline annotations.
- Both surfaces open as tabs; the IDE's native **Split Right** command can place them side by side. A product can persist that workspace layout.
- Symbol selection updates Inspector, xrefs, and the decompiler document together.
- Analysis and debugger execution are deterministic synthetic data; no Ghidra binaries or APIs are
  linked. Backend discovery alone does not run `analyzeHeadless` or attach a target.
- The REPL validates console, history, result typing, and navigation workflows but deliberately does
  not evaluate arbitrary Python or Java. Mutation and trace sample scripts remain blocked until the
  corresponding real adapter capabilities exist.
