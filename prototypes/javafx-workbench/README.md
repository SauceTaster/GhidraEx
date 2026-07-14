# GhidraFX Workbench Prototype

An intentionally clean-break JavaFX 26 workbench around a small, toolkit-neutral
analysis-engine contract. It is a UX and architecture spike, not a Ghidra patch.
The included synthetic engine makes the shell runnable before any Ghidra adapter
exists.

## What is implemented

- Dense, dark analyst workbench with project and symbol navigation
- A 100,000-row synthetic program projected as asynchronous, immutable 256-row semantic windows
- A virtualized, syntax-colored decompiler view
- Details/xrefs inspector and persistent split/tab layout
- Searchable command palette (`Cmd+K` on macOS, `Ctrl+K` elsewhere)
- Background analysis progress, phase reporting, and cancellation
- Native Debugger workspace with captured/past snapshots, emulator forking, atomic
  trace/thread/frame/snapshot coordinates, registers, stack, watches, and write-state labeling
- Capability-gated Scripting & REPL workspace with history, typed outcomes, mutation approval, and
  explicit process/network blocking
- Backend & Extensions inventory that distinguishes an on-disk Ghidra distribution from an
  interactive connection and gates extension capabilities independently
- Toolkit-neutral engine, task, model, and command APIs
- Unit tests for nonblocking listing reads, response reversal, generation changes, disposal,
  bounded symbol/decompiler/xref reads, queue replacement, payload rejection, deterministic listing
  data, analysis tasks, and
  command ranking/execution, debugger coordinates, script policy, and backend/plugin states

The fixture still enters through `dev.ghidraex.engine.AnalysisEngine`, but no interactive control,
cell, getter, or FX event handler invokes that interface. `SyntheticSemanticListingProvider`
adapts bounded listing calls, while `AsyncEngineReads` owns contextual symbol, decompiler, and xref
loads and `AsyncAnalysisLauncher` owns analysis. The shared `AsyncListingController` and
`AsyncContextualReadController` accept only the latest exact request/context/query; one
`Platform.runLater` gateway applies immutable accepted snapshots. A future Ghidra or RPC adapter
can replace those loaders without allowing Swing, JavaFX, mutable Ghidra objects, or transport
latency to leak into rendering.

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

Set `GHIDRA_HOME` before launch to make the runtime inventory probe an official distribution. The
UI reports that installation as **detected**, never connected: the current Listing/Decompiler and
debugger fixtures remain synthetic, arbitrary Ghidra API evaluation remains blocked, and no
extension receives process or write authority. The independently runnable real batch bridge is in
[`../../integration/ghidra`](../../integration/ghidra/README.md).

## Prototype architecture

```text
SyntheticAnalysisEngine       persistent sidecar (next)
          \                         /
           +-- contextual loaders -+       bounded worker pools
                         |
 ViewStateReducer + listing/contextual controllers
                         |
                   Platform.runLater
                         |
       immutable pane snapshots + native JavaFX controls
```

The repository's implemented `analyzeHeadless` bridge already proves real one-shot import,
analysis, and bounded facts export. It is not yet connected to this interactive facade.

The listing intentionally uses a fixed-cell `ListView` over at most 256 local semantic rows; it
does not create or retain a 100,000-item observable list. A location request preserves complete
runtime/program/generation context, schedules one bounded provider call away from the FX thread,
and atomically replaces the current window only when the reducer accepts it. `LOADING`, `CURRENT`,
`STALE`, `PARTIAL`, `FAILED`, and `RESYNCING` are visible states. Analysis advances the fixture
content generation and refreshes without mixing old and new rows. The JavaFX adapter admits two
active provider calls plus eight queued calls; saturation is an explicit failed/stale request rather
than an unbounded queue or an FX-thread wait. See the shared
[`STATE_SPINE.md`](../../integration/view-state/STATE_SPINE.md) proof contract. A production port
should benchmark a Canvas/semantic-overlay renderer against cells before committing to either.

Layout state is stored with `java.util.prefs.Preferences` under
`dev/ghidraex/fx-workbench`.
