# IntelliJ Workbench Prototype

This is a runnable IntelliJ Platform **plugin prototype**, not a repackaged custom IDE distribution. That is deliberate: it lets us test the parts of the IntelliJ option that matter—tool windows, editors, actions, keymaps, background work, layout persistence, and notifications—without first taking ownership of product branding, updates, and distribution.

The target is IntelliJ IDEA Community 2025.1.5 (platform branch `251`, Java 21). Development and Gradle can run on JDK 25; Java sources are compiled with `--release 21`, matching the target platform runtime.

## What is implemented

- **RE Projects** tool window with a synthetic `orbit-controller.bin` program.
- **RE Symbols** sortable table whose selection drives workbench context, with live filtering across symbol name, address, kind, and signature.
- **RE Inspector** context-sensitive symbol and analysis details.
- A native center-editor listing with **100,000 logical instructions**. `JBTable` virtualizes painting and the table model fetches immutable 512-row engine pages into a bounded eight-page LRU cache, so startup does not construct a giant document or 100,000 components.
- A native read-only editor tab for C-like decompilation, backed by a `LightVirtualFile`.
- Platform actions available through **Tools → Reverse Engineering**, **Find Action**, and Search Everywhere's Actions results.
- `Ctrl+Alt+G` opens the program; `Ctrl+Alt+Shift+G` starts analysis. Shortcuts can be remapped through the normal IntelliJ keymap UI.
- Cancellable, determinate background analysis in the IDE status bar, followed by a platform notification and live symbol-table refresh.
- A toolkit-neutral engine interface with deterministic tests. No IntelliJ or Swing type crosses that boundary.

Double-click a project to open it. Select symbols to update the Inspector; double-click a symbol to navigate to its address in the listing.

## Run it

From this directory:

```bash
export JAVA_HOME="/path/to/jdk-25"
./gradlew test buildPlugin
./gradlew runIde
```

If you keep an optional repository-local toolchain, set `JAVA_HOME` to
`$(cd ../.. && pwd)/.toolchains/jdk-25/Contents/Home` instead.

The standard `runIde` task does not stop at IntelliJ's project selector. It copies the bundled
[`demo-project`](demo-project) into the ignored `build/runIde-demo-project` directory, opens that
disposable copy, then automatically opens the virtualized listing and decompiler and shows the RE
Projects, Symbols, and Inspector tool windows. No project creation or menu action is required.

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
      toolkit-neutral AnalysisEngine
                    │
       SyntheticAnalysisEngine (now)
                    │
          GhidraEngineAdapter (next)
```

The important seam is [`AnalysisEngine`](src/main/java/dev/ghidraex/engine/AnalysisEngine.java). Its coarse operations return immutable program, symbol, paged listing, decompilation, and analysis values. The listing editor reports 100,000 rows but only requests a 512-row page when Swing asks to paint or select a row. A real adapter can initially call Ghidra in-process; the same page shape can later become a batched RPC boundary if engine isolation becomes desirable.

## Why a plugin before a custom product

A custom IntelliJ-based product is possible, but it adds a second decision set: platform source builds, product modules, application info/branding, bundled plugin policy, patch/update feeds, installers, signing, and JetBrains-platform license/notice work. None of that changes whether the workbench interaction model succeeds.

If this slice performs well, promote it in two steps:

1. Replace `SyntheticAnalysisEngine` with an in-process `GhidraEngineAdapter` and exercise large listings, decompiler updates, graph views, cancellation, and transaction semantics.
2. Decide whether to ship the plugin on a supported IntelliJ distribution or maintain a branded custom product. The UI code and engine boundary remain applicable to either route.

## Intentional prototype limits

- Listing rows are structured and virtualized, but do not yet provide per-token styling, keyboard field navigation, or inline annotations.
- Both surfaces open as tabs; the IDE's native **Split Right** command can place them side by side. A product can persist that workspace layout.
- Symbol selection updates Inspector context, while decompilation remains the initially selected function.
- The backend is deterministic synthetic data; no Ghidra binaries or APIs are linked.
