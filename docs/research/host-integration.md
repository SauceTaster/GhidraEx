# Native host integration

## Common boundary

JavaFX, IntelliJ Platform, and VS Code / Code OSS should feel native without independently
reimplementing Ghidra semantics. Each host owns presentation, commands, focus, keyboard routing,
accessibility, clipboard, layout, and cached view models. The broker owns resolution, identity,
authority, tasks, persistence semantics, and Ghidra-facing behavior.

The host adapter consists of:

```text
generated Protobuf messages
        +
host-neutral session/state reducer
        +
host-native views, commands, editors, debugger, terminal, tasks, and notifications
```

The reducer consumes snapshots and ordered events and produces explicit `loading`, `current`,
`stale`, `partial`, `failed`, `cancelled`, `resyncing`, and `legacy interaction required` states.
It does not contain host widgets or Ghidra classes. Shared conformance fixtures drive the reducer in
all three products.

## Tradeoff matrix

| Dimension | JavaFX | IntelliJ Platform | VS Code / Code OSS |
| --- | --- | --- | --- |
| Product control | Highest: complete shell, layout, rendering, packaging | High inside IDE constraints and release cadence | High for commands/editors/trees/debug/tasks; shell remains VS Code |
| Native desktop fidelity | Purpose-built reverse-engineering app | Excellent IDE navigation, editor, tool-window, search and refactoring idioms | Excellent lightweight editor/workspace idioms and broad remote ecosystem |
| Browser reach | None without a separate host | JetBrains thin client is not a general browser product | First-class web extension profile, with reduced APIs |
| Existing ecosystem leverage | Lowest; build most workbench services | Rich indexing/editor/action/debugger/platform services | Rich language, DAP, terminal, task, SCM, tree, command and remote integration points |
| UI performance control | Highest, but virtualized listing/graph work is ours | Strong editor infrastructure; custom high-density fields still require care | Native text editor scales well; complex 2-D listing/graph may need focused custom surfaces |
| Ghidra Swing compatibility | `SwingNode` can host a bounded `JComponent`; top-level/modal flows still need separate visible Swing window | Embedding Ghidra Swing risks EDT, class-loader and JDK conflicts; use separate compatibility process | Cannot embed Swing; launch/focus separate compatibility process |
| Runtime coupling risk | Manageable if engine stays out of UI JVM | Highest temptation to load Ghidra into IDE JVM; explicitly forbidden | Low: Node/web worker already forces a service boundary |
| Remote development | Must be designed and shipped | Platform-supported split frontend/backend, but plugin placement matters | Excellent local/SSH/container/tunnel/web variants; extension-host location matters |
| Platform churn | JavaFX/JDK release and native packaging | IntelliJ API/build compatibility and threading model | VS Code API version and web/desktop capability split |
| Best role | Full standalone reference product and deepest compatibility UX | Power-user IDE product | Broadest distribution, remote/browser product, automation-friendly workbench |

## Shared semantic surfaces

| Surface | Required semantic model | Why a generic host feature is insufficient |
| --- | --- | --- |
| Project browser | graph over layered local/repository entries, typed IDs, links, same-name file/folder | filesystem APIs collapse identity and shadow/reveal behavior |
| Listing | sparse defined units + gaps, offcuts, multi-field rows, logical/parsed ranges | flat text loses address/field semantics unless backed by semantic spans |
| Decompiler | result-local tokens/p-code/high variables with generation and mapping | text offsets are unstable after formatting or reanalysis |
| Symbol/reference/data views | paged query resources with semantic row IDs | host tables cannot own Ghidra iterators or numeric DB keys |
| Graphs | declared graph model and result identity separate from geometry/overlays | there is no singular Ghidra CFG and no native graph API in the IDEs |
| Diff/VT/merge | compound job with source/destination generations and conflict decisions | two editors alone do not supply transaction or persistence semantics |
| Debugger | connection/target/trace/time/platform/control tuples | DAP state is useful presentation but does not model trace/emulator history fully |
| Script/REPL | package/runner lease, capabilities, quotas, structured proposals | terminal execution is not a sandbox or Ghidra transaction model |
| Extensions | engine, portable-semantic, legacy-Swing, or host-specific tier | Ghidra Swing plugin binaries are not cross-toolkit UI plugins |

## JavaFX product

### Native plan

- Use JavaFX docking/workspace components, menus, command palette, properties, task center,
  notifications, and accessibility as the standalone product shell.
- Build the Listing as a virtualized semantic field surface. Rows retain address and field IDs;
  selections/highlights are address sets and token mappings rather than character ranges alone.
- Use native `TableView`/`TreeTableView` only for bounded or broker-paged data. The view model holds
  a window and stable semantic selection, not an entire Ghidra table model.
- Render decompiler text natively with semantic spans and result-local token refs. Width/theme
  changes may re-render but do not mutate the underlying result.
- Keep graph geometry in a host workspace layer. A semantic graph refresh attempts to relocate
  nodes; a deliberate relayout replaces geometry without changing result identity.
- Package the UI on its chosen modern JDK/JavaFX runtime. Launch the Ghidra engine with its own
  supported JDK and exact fingerprint.

### Swing compatibility

OpenJFX `SwingNode` embeds Swing content inside JavaFX, but Swing access remains on the EDT and
JavaFX access on the FX Application Thread. It hosts a Swing component, not Ghidra's arbitrary
top-level windows and modal-dialog ownership model
([`SwingNode`](https://openjfx.io/javadoc/17/javafx.swing/javafx/embed/swing/SwingNode.html)).

Use it only for a deliberately bounded compatibility component that has no child top-level windows.
Repository merge, extension dialogs, and complete legacy tools run in a separate visible Swing
process/window under a broker interaction lease. The JavaFX shell shows why it opened, affected
resources, authority, and the typed completion outcome.

### Main costs

JavaFX requires the most work for docking, editor-grade text behavior, keymaps, large virtualized
fields, screen-reader semantics, remote connections, extension APIs, update/packaging, and all
small workbench polish. In return it provides the cleanest standalone architecture and is the best
place to expose recovery, merge, multi-state debugging, and agent approval without IDE compromises.

## IntelliJ Platform product

### Native plan

- A Tool Window supplies project/runtime tree, symbols, references, data types, tasks, audit, and
  debugger-trace explorers.
- `FileEditorProvider`/custom editors open ID-qualified GhidraEx virtual resources. A custom
  `VirtualFileSystem` may support navigation and editor identity, but it is a projection and cannot
  claim filesystem semantics for the project tree.
- Use the editor/action system for navigation history, split tabs, intentions, gutters, inlays,
  hover, usages, keymaps, search, and status. Semantic spans carry broker refs and generations.
- Use native background progress/cancellation and notifications. Never block read/write actions or
  the EDT on a broker RPC.
- Use `ConsoleView` for script/REPL I/O while a separately leased sandbox runner owns execution.
- Integrate debugging through IntelliJ's debugger presentation APIs only after mapping the richer
  trace state. Live DAP-like actions and historical/emulator views remain visibly distinct.
- Store only host layout and lightweight connection identifiers in IDE workspace state. Tokens,
  Ghidra object handles, binary paths on a remote backend, and repository credentials are excluded.

IntelliJ's threading model requires UI work on EDT and separates model read/write access from
background computation; synchronous remote calls inside either are a deadlock/responsiveness risk
([JetBrains threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)).
The adapter snapshots minimal UI context, performs RPC off-thread, then applies a result only if its
generation/request is still current.

### Remote Development

The plugin must declare which pieces run on backend versus thin client. Broker transport, project
identity, workspace filesystem access, artifact upload, and engine discovery belong on the backend
side. UI rendering and local clipboard belong on the frontend. Semantic messages cross that split;
backend-local absolute paths do not.

### Main costs

The platform supplies outstanding editor/workbench infrastructure but has the most API-version,
plugin-class-loader, EDT, read/write-lock, and Remote Development constraints. It can also create a
false sense that Ghidra programs should be PSI/files. Start with native editors and navigation;
introduce PSI/index integration only for a narrowly defined immutable decompiler/source projection
with explicit invalidation.

Do not load Ghidra modules into the IDE JVM. JDK/class-loader/native-library conflicts, process
globals, and fault containment outweigh local call speed.

## VS Code / Code OSS product

VS Code should be the most integrated adapter, not a complete workbench embedded in one webview.
The desktop and web bundles share semantic messages/reducers but select different transport and
filesystem/process capabilities. Official web extensions execute in a browser worker and cannot
use Node APIs ([web extensions](https://code.visualstudio.com/api/extension-guides/web-extensions));
extension hosts also must not access the VS Code UI DOM
([extension capabilities](https://code.visualstudio.com/api/extension-capabilities/overview)).

### Native integration map

| VS Code API | GhidraEx use |
| --- | --- |
| Activity Bar + `TreeDataProvider` | project graph, programs, symbols, data types, references, traces, tasks, plugins; ID/type-qualified items and paged children |
| `TextDocumentContentProvider` + readonly virtual URIs | listing, decompiler, reports, script source and audit artifacts; generation in document identity or visible stale state |
| semantic tokens, decorations, hovers, CodeLens, inlay hints | instruction fields, addresses, source/provenance, mappings, warnings, agent/user highlight layers |
| commands, menus, context keys, keybindings | capability- and state-aware actions; authority appears before command dispatch |
| `DefinitionProvider`, references, symbols, document links | native navigation over broker resolution and xrefs without inventing local symbol identity |
| diagnostics and Problems | analyzer/decompiler/parser/import findings tied to semantic ranges and generations |
| Debug Adapter Protocol integration | live run/pause/step/stack/register/memory presentation; custom trace views for historical schedules/emulator/control authority |
| pseudoterminal + terminal links | REPL/script runner I/O and clickable semantic evidence; terminal itself grants no execution authority |
| `TaskProvider` + progress/cancellation | import, analysis, export, snapshot, diff and maintenance jobs |
| `SourceControl` projection | checkout/dirty/repository status and commands; explicitly not a claim that per-file Ghidra versioning is Git |
| `FileDecorationProvider` | dirty, checkout, hijacked, stale, recovery, conflict, link and inaccessible overlays |
| authentication/secrets APIs | broker credential handle only; secrets never appear in URIs, settings, output, or webview messages |
| notebooks, testing, language client | later structured analysis/script experiments and conformance runners, when the semantic contract justifies them |

Listing and decompiler should start as native readonly documents with semantic providers and tightly
controlled edit commands. A focused custom surface is justified only where VS Code has no native
representation—for example, a function graph, bit/byte memory grid, timeline, structure layout, or
merge conflict visualizer. Such a surface receives narrow typed messages for one document; it does
not host the entire GhidraEx application or own broker credentials.

### Desktop, remote, and web placement

VS Code can place extensions in local, web, or remote extension hosts depending on installation
and configuration ([extension host](https://code.visualstudio.com/api/advanced-topics/extension-host)).
The connection manager must expose where it runs and where the broker/project live. File pickers
produce upload/artifact handles, never assumptions that both machines share a path.

The web profile has no local Ghidra launch. It discovers/authenticates a remote broker, uses the
unary/server-stream transport profile, stores only a revocable credential through VS Code's secret
facility, and disables commands that require unavailable transfer or legacy UI capabilities.

### Main costs

VS Code's native editor is line/text-centric while Ghidra listing, memory, structures, graphs, and
trace time are multidimensional. Custom surfaces will still be necessary, but they should be
document-scoped exceptions. Web extensions also remove Node/native gRPC, process launch, and local
filesystem assumptions. The benefit is the widest deployment reach and an unusually rich set of
native integration points for navigation, diagnostics, debugging, tasks, terminals, commands,
remote workspaces, and automation.

## Cross-host command rules

1. `Save` means persist current local domain-object changes when that content type supports it. It
   never silently means repository check-in.
2. Auto-save may persist host documents or local program content only under explicit policy. It
   never resolves recovery, checks in, upgrades storage, runs analysis, or controls a target.
3. Close shows separate dirty, unsaved, recovery, checkout, remote divergence, running job, and
   live-target states. A single “Do you want to save?” prompt is insufficient.
4. Navigation, selection, highlight, focus, and control authority are separate state channels.
5. Every view visibly represents partial/stale/unmapped/unknown state; blank content is not an
   acceptable error or cancellation UI.
6. Clipboard and exported artifacts are frontend/user actions with data-egress policy. Backend
   paths and Java clipboard objects never cross the boundary.
7. Capability absence disables/hides a command with an explanation. Detecting a Ghidra install
   does not imply repository write, script, plugin, debugger, or agent authority.

## Sequencing

1. Build the shared reducer and v2 vertical slice with the JavaFX host as the full-state reference.
2. Implement the same project tree, sparse listing, decompiler result, tasks, stale handling, and
   one rename transaction in VS Code using native APIs.
3. Implement the same slice in IntelliJ with correct threading and Remote Development placement.
4. Add read-only diff/graph/source artifacts, then compound VT/merge job presentation.
5. Add captured-trace reads before live target control.
6. Add sandboxed script packages and agents after proposal/approval/audit conformance exists.
7. Reduce the Swing compatibility inventory feature by feature; never remove it based only on a
   happy-path replacement.
