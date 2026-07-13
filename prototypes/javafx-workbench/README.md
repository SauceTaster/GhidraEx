# GhidraFX Workbench Prototype

An intentionally clean-break JavaFX 26 workbench around a small, toolkit-neutral
analysis-engine contract. It is a UX and architecture spike, not a Ghidra patch.
The included synthetic engine makes the shell runnable before any Ghidra adapter
exists.

## What is implemented

- Dense, dark analyst workbench with project and symbol navigation
- A 100,000-row virtualized synthetic listing fetched as bounded immutable pages
- A virtualized, syntax-colored decompiler view
- Details/xrefs inspector and persistent split/tab layout
- Searchable command palette (`Cmd+K` on macOS, `Ctrl+K` elsewhere)
- Background analysis progress, phase reporting, and cancellation
- Toolkit-neutral engine, task, model, and command APIs
- Unit tests for deterministic listing data, symbol search, analysis tasks, and
  command ranking/execution

The important boundary is `dev.ghidraex.engine.AnalysisEngine`. Listing access is
batched through `listingWindow(start, count)` rather than exposed as per-row engine
calls. A future Ghidra or RPC adapter can implement that interface without allowing
Swing, JavaFX, or mutable Ghidra domain objects to leak across it.

## Run

Requirements: JDK 25. The checked-in wrapper provides Gradle 9.1.

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew test
./gradlew run
```

When using an optional repository-local toolchain, the commands are:

```bash
export JAVA_HOME="$(cd ../.. && pwd)/.toolchains/jdk-25/Contents/Home"
./gradlew clean test
./gradlew run
```

Use **Run analysis** in the bottom task strip to exercise background work and
cancellation. Open the command palette and choose **Reset saved layout** to
restore the default split proportions.

## Prototype architecture

```text
SyntheticAnalysisEngine       future Ghidra adapter
          \                         /
           +---- AnalysisEngine ---+
                     |
        immutable viewport/search DTOs
                     |
              JavaFX workbench
```

The listing intentionally uses `ListView` with a fixed cell size and a paged
`ObservableList`; it does not create 100,000 scene-graph rows. The engine caps a
window at 512 rows, while the UI requests 256-row pages and retains only the 12
most recently used pages. The decompiler uses the same control-level virtualization
strategy. A production port should preserve this viewport-oriented boundary and
benchmark a Canvas/semantic-overlay renderer against cells before committing to
either.

Layout state is stored with `java.util.prefs.Preferences` under
`dev/ghidraex/fx-workbench`.
