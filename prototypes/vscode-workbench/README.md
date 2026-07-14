# GhidraEx native VS Code / Code OSS workbench prototype

This fourth experiment is one Code OSS extension with desktop Node and browser WebWorker entry
bundles. Its workbench is built from native VS Code integration points, not from an HTML application
placed inside a `WebviewPanel`. The same platform-neutral VSIX is intended for VS Code, Code OSS,
and VSCodium.

`GhidraEx: Open Workbench` reveals a GhidraEx Activity Bar container and opens the Listing and
Decompiler as ordinary read-only editor documents in a split. VS Code therefore owns their tabs,
splits, search, navigation history, minimap, Outline, theming, focus behavior, accessibility, and
restoration.

The prototype contributes:

- Programs, Symbols, Analysis, Agent Evidence, Debug Sessions, Scripting & REPL, and
  Capabilities & Plugins TreeViews in one Activity Bar container;
- native Listing documents that project bounded 4,096-row windows from a 100,000-row fixture, plus
  stable per-function Decompiler documents;
- listing grammar and semantic highlighting, bounded visible-range decorations, Outline symbols,
  CodeLens, hover, go-to-definition, and find-references;
- Command Palette actions, an address Input Box, symbol Quick Pick, editor/view actions,
  contextual menus, and keybindings;
- a cancellable native progress notification, synchronized Analysis TreeView, Status Bar item, and
  Log Output channel;
- deterministic agent evidence in the Problems panel, read-only Comment threads, Code Actions, and
  the Agent Evidence TreeView;
- an inline Debug Adapter Protocol implementation that exercises Run and Debug, stack frames,
  scopes, variables, listing breakpoints, stepping, pause/continue, Debug Console evaluation, and
  debug-to-listing synchronization; and
- a native pseudoterminal command REPL, editable `.gxscript` documents, script templates, script
  output logging, backend selection, capability reporting, and explicit plugin/bridge states.

There is no webview in the main workbench. A future control-flow, call, or data-flow graph could
justify a focused webview because it needs a spatial canvas, zooming, and edge interaction. Even
then, the extension host should own program state and navigation; the graph should not grow into a
second application shell.

## Run it

Requirements: Node.js 22 or later and VS Code 1.100 or later.

```bash
npm ci
npm test
npm run open:desktop
```

`open:desktop` builds the extension and opens an isolated VS Code Extension Development Host. It
does not reuse or modify the normal VS Code profile. The included demo fixture opens the workbench
automatically; **GhidraEx: Open Workbench** in the Command Palette and the GhidraEx Activity Bar
item are idempotent ways to reveal it again.

Run the same extension through VS Code's browser WebWorker extension host:

```bash
npm run open:web
```

Run the automated browser-host extension smoke:

```bash
npm run test:web
```

Build the installable artifact:

```bash
npm run package
```

The expected development output is `dist/ghidraex-vscode-workbench.vsix`. Install it into VS Code with:

```bash
code --install-extension dist/ghidraex-vscode-workbench.vsix --force
```

Or into VSCodium:

```bash
codium --install-extension dist/ghidraex-vscode-workbench.vsix --force
```

VSCodium is not installed in this workspace. The extension avoids proposed APIs, product checks,
native modules, and Marketplace-only dependencies, but compatibility with an actual VSCodium
binary remains unverified.

## Verification status

Verification of the native rewrite and advanced workflow slice is **complete**:

- strict TypeScript checking passed;
- unit tests cover the engine, semantic state reducer, bounded document projection, capability
  resolver, REPL, pseudoterminal, inline debug adapter, and all native TreeView providers;
- both the desktop Node and browser WebWorker extension bundles built;
- the compact VSIX contains only the Node/Web extension bundles, Activity Bar SVG, listing/script
  grammars, language configuration, license, and extension metadata/documentation—no workbench
  HTML, CSS, or webview bundle;
- `npm run test:web` exited with status 0 and asserted bounded listing identity, first/final window
  addresses, cross-window global-row navigation, listing/decompiler languages and content, visible native editors, an Outline symbol,
  definitions/references, Problems diagnostics, the evidence Code Action, nested TreeView-style
  navigation, refresh, a complete inline DAP session, bounded command-script execution, the native
  pseudoterminal, duplicate-analysis suppression, and cancellation through public commands;
- manifest/provider coverage and browser-host activation verify the seven contributed GhidraEx
  TreeViews alongside the native Listing/Decompiler split, Problems, Status Bar state, CodeLens,
  cross-window navigation with synchronized decompilation, and native analysis progress; and
- prior DOM inspection found two Monaco editors, native tree widgets, and zero `.webview` elements.

The isolated desktop launcher was fixed to keep its macOS Unix-socket path below the platform
limit. The desktop log confirms extension activation and that the native workbench opened with no
webview. Cancellation behavior is covered by unit and browser-host command tests, and native cancel
controls were rendered; an interactive cancel-button click is not claimed as separately verified.
VSCodium remains untested because it is not installed locally.

## Architecture

Both runtime bundles register the same stable VS Code APIs:

```text
Activity Bar TreeViews          Native editor TextDocuments
Programs / Symbols              Listing / Decompiler / GX scripts
Analysis / Evidence             Outline / hover / refs / CodeLens
Debug / Scripts / Capabilities             |
              \                           /
                 extension-host coordinator
              /       |          |          \
 Problems + Comments  DAP    pseudoterminal  Quick Pick + commands
                         \       /
                   toolkit-neutral engine facade
                      /                       \
             Node desktop host          browser WebWorker host
```

The synthetic Listing provider projects at most 4,096 semantic rows into any native text document.
It fetches 256-row pages asynchronously, retains a bounded projection cache, and stores an explicit
editor-line-to-semantic-row map. Navigation uses global semantic rows to select a stable 4K shard;
language providers, evidence, DAP sources, decorations, and commands translate through the map
instead of assuming that an editor line is a program row. VS Code still owns rendering and viewport
virtualization, but neither VS Code nor the extension materializes the complete program document.

Listing and Decompiler URIs include runtime, runtime epoch, program, content generation, address
space, and bounded-window/result location. Decompiler URIs remain stable per containing function.
A host-neutral TypeScript reducer mirrors the shared state-spine scenarios: monotonic requests,
exact-context acceptance, explicit stale/partial/resyncing states, event-gap recovery, overlay and
offcut identity, independent location/selection/highlights, and disposal-safe late completion.
The live controller begins and completes those reducer requests around each bounded provider read,
owns programmatic cursor events as part of the initiating navigation transaction, and publishes
freshness plus authority state through the native Status Bar, tooltip, and VS Code context keys.
This bounded fixture uses JavaScript safe integers for request/event counters; the v2 generated
Protobuf boundary must preserve wider integer identities without coercing them through `number`.

The reducer is deliberately a read-only proof, not a backend authority layer. Debugger, script,
plugin, repository, and agent mutation surfaces remain visibly synthetic or unavailable until their
leases, capability descriptors, proposal/apply flow, and audit contracts exist.

Every presentation read now crosses the asynchronous `NativeReadService` boundary. Results carry
runtime/program/generation identity, a result ID, completeness (`COMPLETE`, `PARTIAL`, or
`TRUNCATED`), and bounded warnings. The service validates all untrusted fields and aggregate text
budgets before publishing, rejects regressive snapshots and mismatched identities, quarantines
reads while resynchronizing, aborts superseded/invalidation/disposal work at the transport, and
keeps small per-kind context-qualified LRU caches. The fixture transport deliberately yields before
calling the deterministic engine, so replacing it with gRPC, Connect, or HTTPS cannot accidentally
depend on synchronous extension-host access.

Some VS Code presentation APIs are inherently synchronous: `getTreeItem`, cached Code Actions,
visible-range decoration mapping, and Status Bar/selection event rendering. They consume immutable
bounded projections only; their owning providers prefetch asynchronously and expose loading,
partial, stale, resynchronizing, or error state while retaining the last valid projection. Language
providers that accept promises and cancellation tokens await the transport directly. This split is
the required integration pattern for both desktop and browser extension hosts; no provider may
reach through to the fixture engine.

## Debugging contract

`GhidraEx: Debug: Start Synthetic Debug Session` starts a real VS Code debug session through an
inline adapter, so the normal Run and Debug UI owns session controls, Call Stack, Variables,
breakpoints, and the Debug Console. The adapter deliberately models a deterministic trace. Its
Output event, Variables scope, Debug Sessions TreeView, Status Bar, and `backend` evaluation all say
that no process, Ghidra Debugger target, or TraceRmi connection exists.

This is enough to test awkward debugger lifecycle states without shipping an executable adapter.
A production adapter should translate DAP requests to Ghidra Debugger/TraceRmi operations behind a
session-scoped transport and retain the same native UI contract.

At each stop/location, the adapter asynchronously prefetches one bounded immutable snapshot:
program summary, current and caller decompilations, and capped cross-references. It delays the DAP
`stopped` event until that snapshot settles, then serves stack frames, Program variables, and Debug
Console expressions synchronously from it. Location changes and disposal abort the old prefetch;
loading, partial, and failure state remain explicit instead of blocking a DAP request handler.

## Scripting and plugin contract

The Script Console is a native VS Code pseudoterminal. It accepts a deliberately bounded command
set (`program`, `goto`, `decompile`, `xrefs`, `evidence`, `analyze`, `capabilities`, and `plugins`)
instead of evaluating arbitrary JavaScript. `.gxscript` editor documents run the same commands and
send results to a Log Output channel. Python/PyGhidra and Java GhidraScript templates can be created
in ordinary editors, but execution is rejected with the current backend limitation.

Capabilities & Plugins separates built-in prototype providers, bridge capabilities, and future
Ghidra extensions. Selecting `local` or `remote` does not relabel synthetic results as Ghidra: the
view reports the requested backend, why it is unavailable, and that the synthetic engine remains
the effective read-only fallback. A copyable report makes these states useful in bug reports and
agentic test runs.

## Real Ghidra seam

The deterministic TypeScript engine keeps this prototype independently runnable. A production
desktop Node adapter can connect to or launch a local JVM. A browser WebWorker cannot launch Java,
open arbitrary local files, or create child processes, so a browser-hosted Ghidra backend must be an
authenticated remote HTTPS service or a purpose-built WebAssembly engine. The contributed settings
make those contracts inspectable now: local mode requires workspace trust and a Ghidra path; remote
mode requires a valid HTTPS endpoint. Neither is reported ready because this prototype does not yet
ship either transport adapter.

That transport belongs behind the coarse operations in `src/core/readService.ts`; the synthetic
adapter is the only code allowed to call `src/core/engine.ts` directly. TreeViews and document
providers receive immutable summaries, bounded listing ranges, decompiler text, references,
evidence, and progress events—not Ghidra `Program` identities or process/network authority.

## Native agent evidence

The evidence in this prototype is deterministic and read-only; it is meant to exercise agentic UI
states rather than claim a live model integration. Findings and hypotheses appear as Diagnostics in
Problems, provenance-bearing Comment threads anchored to listing ranges, Code Actions, and TreeView
items. Future model tools can call the same bounded engine operations, while proposed mutations
should use native diff/review flows and explicit confirmation.

## Browser, security, and accessibility

- The shared extension source avoids Node APIs; desktop-only local-engine code belongs behind a
  separately bundled adapter.
- Virtual and untrusted workspace behavior is declared in the extension manifest.
- Virtual-document URIs are parsed and checked against the active runtime, program, content
  generation, address space, and bounded projection.
- Program content is read-only and does not receive filesystem, process, or network authority.
- TreeViews, text editors, Quick Pick, progress, Problems, Comments, Outline, and the Status Bar use
  VS Code's theme, keyboard, high-contrast, screen-reader, focus, and layout behavior.
- Any future graph webview must use a restrictive CSP, packaged resources, runtime-validated
  messages, and compact restorable state.

The desktop/web runtime constraints follow the official
[VS Code web extension guide](https://code.visualstudio.com/api/extension-guides/web-extensions),
and the native workbench structure follows the official
[Tree View](https://code.visualstudio.com/api/extension-guides/tree-view),
[virtual document](https://code.visualstudio.com/api/extension-guides/virtual-documents), and
[programmatic language features](https://code.visualstudio.com/api/language-extensions/programmatic-language-features)
guides.
