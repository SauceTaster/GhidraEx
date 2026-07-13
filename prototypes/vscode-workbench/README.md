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

- Programs, Symbols, Analysis, and Agent Evidence TreeViews in one Activity Bar container;
- a native 100,000-line virtual Listing document and stable per-function Decompiler documents;
- listing grammar and semantic highlighting, bounded visible-range decorations, Outline symbols,
  CodeLens, hover, go-to-definition, and find-references;
- Command Palette actions, an address Input Box, symbol Quick Pick, editor/view actions,
  contextual menus, and keybindings;
- a cancellable native progress notification, synchronized Analysis TreeView, Status Bar item, and
  Log Output channel; and
- deterministic agent evidence in the Problems panel, read-only Comment threads, Code Actions, and
  the Agent Evidence TreeView.

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

The expected output is `dist/ghidraex-vscode-workbench-0.1.0.vsix`. Install it into VS Code with:

```bash
code --install-extension dist/ghidraex-vscode-workbench-0.1.0.vsix --force
```

Or into VSCodium:

```bash
codium --install-extension dist/ghidraex-vscode-workbench-0.1.0.vsix --force
```

VSCodium is not installed in this workspace. The extension avoids proposed APIs, product checks,
native modules, and Marketplace-only dependencies, but compatibility with an actual VSCodium
binary remains unverified.

## Verification status

Verification of the native rewrite is **complete**:

- strict TypeScript checking passed;
- twelve unit tests passed after the legacy webview, protocol, and navigation sources were deleted;
- both the desktop Node and browser WebWorker extension bundles built;
- the compact VSIX contains only the Node/Web extension bundles, Activity Bar SVG, listing grammar,
  license, and extension metadata/documentation—no workbench HTML, CSS, or webview bundle;
- `npm run test:web` exited with status 0 and asserted the 100,000-line listing, first/final
  addresses, listing/decompiler languages and content, visible native editors, an Outline symbol,
  definitions/references, Problems diagnostics, the evidence Code Action, nested TreeView-style
  navigation, refresh, duplicate-analysis suppression, and cancellation through public commands;
- interactive browser inspection showed the four GhidraEx TreeViews, native Listing/Decompiler
  split, Problems, Status Bar state, CodeLens, line-100,000 navigation with synchronized
  decompilation, and native analysis progress through completion; and
- DOM inspection found two Monaco editors, five native tree widgets, and zero `.webview`
  elements.

The isolated desktop launcher was fixed to keep its macOS Unix-socket path below the platform
limit. The desktop log confirms extension activation and that the native workbench opened with no
webview. Cancellation behavior is covered by unit and browser-host command tests, and native cancel
controls were rendered; an interactive cancel-button click is not claimed as separately verified.
VSCodium remains untested because it is not installed locally.

## Architecture

Both runtime bundles register the same stable VS Code APIs:

```text
Activity Bar TreeViews       Native editor TextDocuments
Programs / Symbols           Listing / Decompiler
Analysis / Evidence          Outline / hover / refs / CodeLens
             \                 /
              extension-host coordinator
             /        |          \
 Problems + Comments  progress    Quick Pick + commands
                      |
             toolkit-neutral Engine facade
             /                         \
       Node desktop host          browser WebWorker host
```

The synthetic Listing provider generates engine data in 256-row chunks without retaining an
`Instruction[100000]`. VS Code receives one read-only 100,000-line text document and owns viewport
virtualization. The engine cache remains bounded, but the resulting text buffer is intentionally
whole-document for this comparison. Real multi-million-instruction programs should be exposed as
function-, block-, or segment-sized virtual documents rather than one enormous buffer.

Listing and Decompiler URIs include the program identity and analysis revision. Decompiler URIs are
stable per containing function, which gives editor history and restoration meaningful document
identities. Selection changes coordinate the editor, decompiler, Activity Bar views, and Status Bar
without giving those UI surfaces direct engine authority.

## Real Ghidra seam

The deterministic TypeScript engine keeps this prototype independently runnable. A production
desktop Node adapter can connect to or launch a local JVM. A browser WebWorker cannot launch Java,
open arbitrary local files, or create child processes, so a browser-hosted Ghidra backend must be an
authenticated remote HTTPS service or a purpose-built WebAssembly engine.

That transport belongs behind the coarse operations in `src/core/engine.ts`. TreeViews and document
providers should receive immutable summaries, bounded listing ranges, decompiler text, references,
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
- Virtual-document URIs are parsed and checked against the active program and revision.
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
