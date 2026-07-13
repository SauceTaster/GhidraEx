import "./style.css";
import { workbenchApi } from "./api";
import { clampProgress, closestListingRow, filterCommands, normalizeAddress } from "./model";
import type { AnalysisState, Command, ListingRow, SymbolRecord, WorkbenchSnapshot } from "./types";

const root = document.querySelector<HTMLDivElement>("#app");
if (!root) throw new Error("Missing application root");

root.innerHTML = `
  <div class="boot-wash" aria-hidden="true"></div>
  <div class="app-shell">
    <aside class="sidebar" id="sidebar">
      <div class="brand-row">
        <div class="brand-mark"><i></i><i></i><i></i></div>
        <div><strong>GHIDRAEX</strong><span>RE WORKBENCH · 0.1</span></div>
        <button class="icon-button sidebar-close" id="sidebar-close" aria-label="Close project sidebar">×</button>
      </div>

      <section class="target-card" aria-label="Active target">
        <div class="target-icon">QZ</div>
        <div class="target-copy"><span>ACTIVE TARGET</span><strong>quartz-agent</strong><small>ELF · x86-64 · 2.8 MB</small></div>
        <span class="live-dot" title="Target loaded"></span>
      </section>

      <nav class="project-nav" aria-label="Project explorer">
        <div class="nav-heading"><span>PROJECT</span><button aria-label="Project actions">•••</button></div>
        <button class="tree-item tree-parent expanded"><span class="chevron">⌄</span><span class="file-glyph root-glyph">◈</span>Quartz Research</button>
        <div class="tree-children">
          <button class="tree-item active"><span class="file-glyph binary-glyph">01</span><span>quartz-agent</span><em>LIVE</em></button>
          <button class="tree-item"><span class="file-glyph">↗</span><span>libtransport.so</span></button>
          <button class="tree-item"><span class="file-glyph">↗</span><span>libcrypto.so.3</span></button>
          <button class="tree-item muted"><span class="file-glyph">+</span><span>Import binary</span></button>
        </div>
      </nav>

      <section class="symbol-browser">
        <div class="nav-heading"><span>SYMBOLS</span><b id="symbol-count">—</b></div>
        <label class="filter-field">
          <span>⌕</span>
          <input id="symbol-search" type="search" placeholder="Filter symbols" autocomplete="off" aria-label="Filter symbols" />
          <kbd>/</kbd>
        </label>
        <div class="symbol-list" id="symbol-list" aria-live="polite">
          <div class="skeleton-line"></div><div class="skeleton-line short"></div><div class="skeleton-line"></div>
        </div>
      </section>

      <div class="sidebar-footer">
        <div class="engine-orbit"><i></i><i></i><i></i></div>
        <div><span>ENGINE</span><strong id="engine-state">Connecting</strong></div>
        <small id="engine-latency">127.0.0.1</small>
      </div>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <button class="icon-button menu-button" id="menu-button" aria-label="Open project sidebar">☰</button>
        <div class="breadcrumbs"><span>Quartz Research</span><b>/</b><span>quartz-agent</span><b>/</b><strong>FUN_004011d0</strong></div>
        <div class="top-actions">
          <button class="command-trigger" id="command-trigger"><span>⌕</span><span>Jump or run a command</span><kbd>⌘ K</kbd></button>
          <div class="session-pill"><i></i><span>LOCAL SESSION</span></div>
          <button class="avatar" aria-label="Workspace profile">HX</button>
        </div>
      </header>

      <section class="target-strip" aria-label="Binary details">
        <div class="target-title">
          <span class="binary-chip">ELF</span>
          <div><strong id="binary-name">quartz-agent</strong><span id="binary-meta">Loading target metadata…</span></div>
        </div>
        <dl class="target-facts">
          <div><dt>IMAGE BASE</dt><dd id="image-base">—</dd></div>
          <div><dt>ENTRY</dt><dd>0x00401090</dd></div>
          <div><dt>FUNCTIONS</dt><dd id="function-count">—</dd></div>
        </dl>
        <div class="view-tabs" role="tablist" aria-label="Workbench views">
          <button class="active" role="tab" aria-selected="true">Code</button>
          <button role="tab" aria-selected="false">Graph</button>
          <button role="tab" aria-selected="false">Memory</button>
        </div>
      </section>

      <div class="work-grid">
        <section class="panel listing-panel" aria-labelledby="listing-title">
          <header class="panel-header">
            <div><span class="panel-icon">≡</span><strong id="listing-title">DISASSEMBLY</strong><span class="context-label" id="listing-context">.text</span></div>
            <div class="panel-tools">
              <span class="address-badge" id="current-address">0x004011d0</span>
              <button class="icon-button" id="goto-button" aria-label="Go to address">↳</button>
              <button class="icon-button" aria-label="Listing options">•••</button>
            </div>
          </header>
          <div class="column-head" aria-hidden="true"><span>ADDRESS</span><span>BYTES</span><span>INSTRUCTION</span></div>
          <div class="listing-scroll" id="listing" role="table" aria-label="Disassembly listing">
            <div class="listing-empty"><span class="spinner"></span> Requesting listing viewport…</div>
          </div>
          <footer class="panel-footer"><span><i class="key-dot branch"></i>branch</span><span><i class="key-dot call"></i>call</span><span><i class="key-dot annotation"></i>analysis note</span><b id="listing-range">—</b></footer>
        </section>

        <div class="right-stack">
          <section class="panel decompiler-panel" aria-labelledby="decompiler-title">
            <header class="panel-header">
              <div><span class="panel-icon braces">{ }</span><strong id="decompiler-title">DECOMPILER</strong><span class="context-label">C / HIGH</span></div>
              <div class="panel-tools"><span class="success-label"><i></i>SYNCED</span><button class="icon-button" aria-label="Decompiler options">•••</button></div>
            </header>
            <div class="signature" id="signature">int64_t FUN_004011d0(char *param_1, uint32_t param_2)</div>
            <div class="code-view" id="decompiler"><div class="listing-empty"><span class="spinner"></span> Decompiling function…</div></div>
          </section>

          <section class="panel inspector-panel" aria-labelledby="inspector-title">
            <header class="panel-header compact">
              <div><span class="panel-icon">◇</span><strong id="inspector-title">INSPECTOR</strong></div>
              <div class="inspector-tabs"><button class="active">Selection</button><button>Xrefs</button><button>Types</button></div>
            </header>
            <div class="inspector-content" id="inspector">
              <div class="inspector-primary"><span>SELECTION</span><strong>Waiting for engine</strong><small>—</small></div>
            </div>
          </section>
        </div>
      </div>

      <footer class="analysis-bar">
        <div class="analysis-state"><span class="analysis-glyph" id="analysis-glyph">◎</span><div><span>AUTO ANALYSIS</span><strong id="analysis-phase">Ready</strong></div></div>
        <div class="progress-wrap">
          <div class="progress-meta"><span id="analysis-detail">Waiting for engine snapshot</span><b id="analysis-percent">0%</b></div>
          <div class="progress-track"><i id="analysis-progress"></i></div>
        </div>
        <div class="analysis-metrics"><span>FOUND</span><strong id="analysis-functions">0</strong><small>functions</small></div>
        <button class="analysis-button" id="analysis-button" disabled><span>▶</span> Run analysis</button>
        <div class="status-item"><i></i><span id="service-status">SERVICE OFFLINE</span></div>
      </footer>
    </main>
  </div>

  <div class="palette-backdrop" id="palette-backdrop" hidden>
    <section class="command-palette" role="dialog" aria-modal="true" aria-labelledby="palette-title">
      <header>
        <span id="palette-icon">⌕</span>
        <input id="palette-input" autocomplete="off" spellcheck="false" aria-label="Command search" placeholder="Type a command…" />
        <kbd>ESC</kbd>
      </header>
      <div class="palette-label"><span id="palette-title">COMMANDS</span><small>Use ↑↓ to navigate · Enter to run</small></div>
      <div class="palette-results" id="palette-results"></div>
      <footer><span><kbd>↵</kbd> run</span><span><kbd>↑</kbd><kbd>↓</kbd> navigate</span><strong>GHIDRAEX COMMAND DECK</strong></footer>
    </section>
  </div>
  <div class="toast" id="toast" role="status" aria-live="polite"></div>
`;

function required<T extends Element>(selector: string): T {
  const element = document.querySelector<T>(selector);
  if (!element) throw new Error(`Missing element: ${selector}`);
  return element;
}

const ui = {
  symbolSearch: required<HTMLInputElement>("#symbol-search"),
  symbolList: required<HTMLDivElement>("#symbol-list"),
  symbolCount: required<HTMLElement>("#symbol-count"),
  listing: required<HTMLDivElement>("#listing"),
  decompiler: required<HTMLDivElement>("#decompiler"),
  inspector: required<HTMLDivElement>("#inspector"),
  currentAddress: required<HTMLElement>("#current-address"),
  signature: required<HTMLElement>("#signature"),
  analysisButton: required<HTMLButtonElement>("#analysis-button"),
  analysisProgress: required<HTMLElement>("#analysis-progress"),
  analysisPercent: required<HTMLElement>("#analysis-percent"),
  analysisPhase: required<HTMLElement>("#analysis-phase"),
  analysisDetail: required<HTMLElement>("#analysis-detail"),
  analysisFunctions: required<HTMLElement>("#analysis-functions"),
  analysisGlyph: required<HTMLElement>("#analysis-glyph"),
  serviceStatus: required<HTMLElement>("#service-status"),
  engineState: required<HTMLElement>("#engine-state"),
  engineLatency: required<HTMLElement>("#engine-latency"),
  paletteBackdrop: required<HTMLDivElement>("#palette-backdrop"),
  paletteInput: required<HTMLInputElement>("#palette-input"),
  paletteResults: required<HTMLDivElement>("#palette-results"),
  paletteTitle: required<HTMLElement>("#palette-title"),
  paletteIcon: required<HTMLElement>("#palette-icon"),
  sidebar: required<HTMLElement>("#sidebar"),
  toast: required<HTMLDivElement>("#toast"),
};

let snapshot: WorkbenchSnapshot | null = null;
let selectedAddress = "";
let paletteMode: "commands" | "address" = "commands";
let activeCommandIndex = 0;
let searchSequence = 0;
let toastTimer: number | undefined;

function escapeForSelector(value: string): string {
  return CSS.escape(value);
}

function showToast(message: string, tone: "normal" | "error" = "normal"): void {
  window.clearTimeout(toastTimer);
  ui.toast.textContent = message;
  ui.toast.dataset.tone = tone;
  ui.toast.classList.add("visible");
  toastTimer = window.setTimeout(() => ui.toast.classList.remove("visible"), 2800);
}

function updateConnection(connected: boolean): void {
  ui.serviceStatus.textContent = connected ? "SERVICE ONLINE" : "SERVICE OFFLINE";
  ui.serviceStatus.parentElement?.classList.toggle("online", connected);
  ui.engineState.textContent = connected ? "JVM engine ready" : "Connection lost";
  ui.engineLatency.textContent = connected ? "127.0.0.1 · SSE" : "Retrying…";
  ui.analysisButton.disabled = !connected;
}

function renderSymbols(symbols: SymbolRecord[]): void {
  ui.symbolList.replaceChildren();
  ui.symbolCount.textContent = `${symbols.length}`;
  if (!symbols.length) {
    const empty = document.createElement("div");
    empty.className = "symbol-empty";
    empty.textContent = "No matching symbols";
    ui.symbolList.append(empty);
    return;
  }
  for (const symbol of symbols.slice(0, 24)) {
    const button = document.createElement("button");
    button.className = "symbol-row";
    button.dataset.address = symbol.address;
    button.innerHTML = `<i>${symbol.kind === "function" ? "ƒ" : symbol.kind === "import" ? "⇢" : "◆"}</i><span></span><small></small>`;
    child<HTMLSpanElement>(button, "span").textContent = symbol.name;
    child<HTMLElement>(button, "small").textContent = symbol.address.replace("0x00", "");
    button.title = `${symbol.namespace} · ${symbol.refs} references · ${symbol.confidence}% confidence`;
    button.addEventListener("click", () => void navigateTo(symbol.address, symbol.name));
    ui.symbolList.append(button);
  }
}

function requiredWithin<T extends Element>(selector: string, parent: ParentNode): T {
  const element = parent.querySelector<T>(selector);
  if (!element) throw new Error(`Missing child element: ${selector}`);
  return element;
}

// Overload-free local helper for nodes created above.
function child<T extends Element>(parent: ParentNode, selector: string): T {
  return requiredWithin<T>(selector, parent);
}

function renderListing(rows: ListingRow[], totalInstructions = rows.length): void {
  ui.listing.replaceChildren();
  const fragment = document.createDocumentFragment();
  rows.forEach((row, index) => {
    const item = document.createElement("button");
    item.className = `listing-row flow-${row.flow}`;
    item.dataset.address = row.address;
    item.setAttribute("role", "row");
    item.setAttribute("aria-label", `${row.address}: ${row.mnemonic} ${row.operands}`);
    item.innerHTML = `<span class="gutter">${index % 7 === 0 ? "•" : ""}</span><span class="row-address" role="cell"></span><span class="row-bytes" role="cell"></span><span class="row-instruction" role="cell"><b></b><em></em><small></small></span>`;
    child<HTMLElement>(item, ".row-address").textContent = row.address;
    child<HTMLElement>(item, ".row-bytes").textContent = row.bytes;
    child<HTMLElement>(item, ".row-instruction b").textContent = row.mnemonic;
    child<HTMLElement>(item, ".row-instruction em").textContent = row.operands;
    child<HTMLElement>(item, ".row-instruction small").textContent = row.annotation;
    item.addEventListener("click", () => selectListingRow(row));
    fragment.append(item);
  });
  ui.listing.append(fragment);
  required<HTMLElement>("#listing-range").textContent = `${rows.length} visible · ${totalInstructions.toLocaleString()} total`;
}

function appendHighlightedLine(target: HTMLElement, text: string, lineNumber: number): void {
  const line = document.createElement("div");
  line.className = "code-line";
  const number = document.createElement("span");
  number.className = "line-number";
  number.textContent = String(lineNumber).padStart(2, "0");
  line.append(number);
  const code = document.createElement("code");
  const tokenPattern = /(\/\/.*$|\b(?:if|else|return|while|for|int64_t|uint32_t|char|void|size_t)\b|\b(?:FUN_[0-9a-f]+|memcmp|memcpy|transport_send|rotate_key)\b|0x[0-9a-f]+|"[^"]*")/gi;
  let cursor = 0;
  for (const match of text.matchAll(tokenPattern)) {
    const index = match.index ?? cursor;
    code.append(document.createTextNode(text.slice(cursor, index)));
    const span = document.createElement("span");
    const token = match[0];
    span.className = token.startsWith("//") ? "tok-comment" : token.startsWith('"') ? "tok-string" : /^0x/i.test(token) ? "tok-number" : /^(if|else|return|while|for|int64_t|uint32_t|char|void|size_t)$/i.test(token) ? "tok-keyword" : "tok-call";
    span.textContent = token;
    code.append(span);
    cursor = index + token.length;
  }
  code.append(document.createTextNode(text.slice(cursor)));
  line.append(code);
  target.append(line);
}

function renderDecompiler(source: string): void {
  ui.decompiler.replaceChildren();
  source.split("\n").forEach((line, index) => appendHighlightedLine(ui.decompiler, line, index + 1));
}

function renderInspector(row?: ListingRow, symbolName?: string): void {
  if (!snapshot) return;
  const info = snapshot.inspector;
  const address = row?.address ?? info.address;
  const flow = row?.flow ?? "normal";
  const name = symbolName ?? info.functionName;
  ui.inspector.replaceChildren();

  const primary = document.createElement("div");
  primary.className = "inspector-primary";
  primary.innerHTML = "<span>ACTIVE LOCATION</span><strong></strong><small></small>";
  child<HTMLElement>(primary, "strong").textContent = name;
  child<HTMLElement>(primary, "small").textContent = `${address} · ${row?.mnemonic ?? info.segment}`;

  const grid = document.createElement("dl");
  grid.className = "inspector-grid";
  const values = [
    ["SEGMENT", info.segment],
    ["FILE OFFSET", info.offset],
    ["FLOW", flow.toUpperCase()],
    ["STACK Δ", `${info.stackDelta >= 0 ? "+" : ""}${info.stackDelta}`],
    ["XREFS IN", String(info.xrefsIn)],
    ["XREFS OUT", String(info.xrefsOut)],
  ];
  for (const [label, value] of values) {
    const item = document.createElement("div");
    const dt = document.createElement("dt");
    const dd = document.createElement("dd");
    dt.textContent = label ?? "";
    dd.textContent = value ?? "";
    item.append(dt, dd);
    grid.append(item);
  }
  const provenance = document.createElement("div");
  provenance.className = "provenance";
  provenance.innerHTML = `<i>AI</i><span><b>TYPE PROVENANCE</b><small></small></span><em>98%</em>`;
  child<HTMLElement>(provenance, "small").textContent = info.prototypeSource;
  ui.inspector.append(primary, grid, provenance);
}

function selectListingRow(row: ListingRow, symbolName?: string): void {
  selectedAddress = row.address;
  ui.currentAddress.textContent = row.address;
  ui.listing.querySelectorAll(".listing-row.selected").forEach((element) => element.classList.remove("selected"));
  const selected = ui.listing.querySelector<HTMLElement>(`[data-address="${escapeForSelector(row.address)}"]`);
  selected?.classList.add("selected");
  selected?.scrollIntoView({ block: "nearest" });
  renderInspector(row, symbolName);
}

async function navigateTo(address: string, symbolName?: string): Promise<void> {
  if (!snapshot) return;
  let rows = snapshot.listing;
  try {
    const viewport = await workbenchApi.listing(address, 50);
    rows = viewport.rows;
    snapshot.listing = rows;
    snapshot.listingInfo = {
      totalInstructions: viewport.totalInstructions,
      minAddress: viewport.minAddress,
      maxAddress: viewport.maxAddress,
      viewportRows: rows.length,
    };
    renderListing(rows, viewport.totalInstructions);
  } catch {
    showToast("Listing service unavailable; using the current viewport", "error");
  }
  const row = closestListingRow(rows, address);
  if (!row) return;
  selectListingRow(row, symbolName);
  showToast(`Viewport centered at ${row.address}`);
  closePalette();
}

function renderAnalysis(state: AnalysisState): void {
  const progress = clampProgress(state.progress);
  ui.analysisProgress.style.width = `${progress}%`;
  ui.analysisPercent.textContent = `${progress}%`;
  ui.analysisPhase.textContent = state.phase;
  ui.analysisDetail.textContent = state.status === "running" ? `Analyzers active · ${(state.elapsedMs / 1000).toFixed(1)}s elapsed` : `State: ${state.status}`;
  ui.analysisFunctions.textContent = state.functionsDiscovered.toLocaleString();
  ui.analysisGlyph.textContent = state.status === "running" ? "◌" : state.status === "complete" ? "✓" : "◎";
  ui.analysisGlyph.classList.toggle("spinning", state.status === "running");
  ui.analysisButton.dataset.running = String(state.status === "running");
  ui.analysisButton.innerHTML = state.status === "running" ? "<span>■</span> Cancel" : "<span>▶</span> Run analysis";
  required<HTMLElement>("#function-count").textContent = state.functionsDiscovered.toLocaleString();
}

function renderSnapshot(data: WorkbenchSnapshot): void {
  snapshot = data;
  required<HTMLElement>("#binary-name").textContent = data.project.binary;
  required<HTMLElement>("#binary-meta").textContent = `${data.project.format} · ${data.project.architecture} · SHA ${data.project.sha256.slice(0, 12)}…`;
  required<HTMLElement>("#image-base").textContent = data.project.imageBase;
  ui.signature.textContent = data.inspector.signature;
  renderSymbols(data.symbols);
  renderListing(data.listing, data.listingInfo.totalInstructions);
  renderDecompiler(data.decompiler);
  renderAnalysis(data.analysis);
  const initial = closestListingRow(data.listing, data.inspector.address) ?? data.listing[0];
  if (initial) selectListingRow(initial);
}

async function toggleAnalysis(): Promise<void> {
  if (!snapshot) return;
  ui.analysisButton.disabled = true;
  try {
    const running = ui.analysisButton.dataset.running === "true";
    const state = running ? await workbenchApi.cancelAnalysis() : await workbenchApi.startAnalysis();
    renderAnalysis(state);
    showToast(running ? "Analysis cancelled" : "Analysis pipeline started");
  } catch (error) {
    showToast(error instanceof Error ? error.message : "Analysis request failed", "error");
  } finally {
    ui.analysisButton.disabled = false;
  }
}

const commands: Command[] = [
  { id: "goto", label: "Go to address…", detail: "Center the listing viewport", shortcut: "⌘ G", keywords: ["jump", "navigate", "offset"], run: () => openPalette("address") },
  { id: "analysis", label: "Toggle auto analysis", detail: "Start or cancel the synthetic pipeline", keywords: ["scan", "analyzers", "cancel"], run: toggleAnalysis },
  { id: "symbols", label: "Focus symbol filter", detail: "Search functions, imports, and labels", shortcut: "/", keywords: ["search", "function"], run: () => { closePalette(); ui.symbolSearch.focus(); } },
  { id: "sidebar", label: "Toggle project explorer", detail: "Show or hide the project sidebar", keywords: ["files", "panel"], run: () => { closePalette(); ui.sidebar.classList.toggle("open"); } },
  { id: "entry", label: "Navigate to entry point", detail: "Jump to 0x00401090", keywords: ["start", "main"], run: () => navigateTo("0x00401090", "entry") },
  { id: "copy", label: "Copy current address", detail: "Place the selected location on the clipboard", keywords: ["clipboard", "address"], run: async () => { await navigator.clipboard.writeText(selectedAddress); closePalette(); showToast(`${selectedAddress} copied`); } },
];

function renderPalette(): void {
  ui.paletteResults.replaceChildren();
  if (paletteMode === "address") {
    const normalized = normalizeAddress(ui.paletteInput.value);
    const row = normalized && snapshot ? closestListingRow(snapshot.listing, normalized) : undefined;
    const result = document.createElement("button");
    result.className = `palette-row active ${normalized ? "" : "disabled"}`;
    result.innerHTML = `<i>↳</i><span><strong></strong><small></small></span><em>ENTER</em>`;
    child<HTMLElement>(result, "strong").textContent = normalized ? `Go to ${normalized}` : "Enter a hexadecimal address";
    child<HTMLElement>(result, "small").textContent = row ? `Nearest loaded instruction: ${row.address}` : "Example: 0x004011d0";
    result.disabled = !normalized;
    result.addEventListener("click", () => { if (normalized) void navigateTo(normalized); });
    ui.paletteResults.append(result);
    return;
  }
  const matches = filterCommands(commands, ui.paletteInput.value);
  activeCommandIndex = Math.min(activeCommandIndex, Math.max(0, matches.length - 1));
  matches.forEach((command, index) => {
    const result = document.createElement("button");
    result.className = `palette-row ${index === activeCommandIndex ? "active" : ""}`;
    result.innerHTML = `<i>${command.id === "analysis" ? "◎" : command.id === "goto" ? "↳" : "◇"}</i><span><strong></strong><small></small></span><em></em>`;
    child<HTMLElement>(result, "strong").textContent = command.label;
    child<HTMLElement>(result, "small").textContent = command.detail;
    child<HTMLElement>(result, "em").textContent = command.shortcut ?? "";
    result.addEventListener("mouseenter", () => { activeCommandIndex = index; renderPalette(); });
    result.addEventListener("click", () => void command.run());
    ui.paletteResults.append(result);
  });
  if (!matches.length) {
    const empty = document.createElement("div");
    empty.className = "palette-empty";
    empty.textContent = "No matching command";
    ui.paletteResults.append(empty);
  }
}

function openPalette(mode: "commands" | "address" = "commands"): void {
  paletteMode = mode;
  activeCommandIndex = 0;
  ui.paletteBackdrop.hidden = false;
  ui.paletteTitle.textContent = mode === "address" ? "NAVIGATE" : "COMMANDS";
  ui.paletteIcon.textContent = mode === "address" ? "↳" : "⌕";
  ui.paletteInput.placeholder = mode === "address" ? "Enter address (hex)…" : "Type a command…";
  ui.paletteInput.value = "";
  renderPalette();
  window.requestAnimationFrame(() => ui.paletteInput.focus());
}

function closePalette(): void {
  ui.paletteBackdrop.hidden = true;
}

function runActivePaletteItem(): void {
  if (paletteMode === "address") {
    const address = normalizeAddress(ui.paletteInput.value);
    if (address) void navigateTo(address);
    return;
  }
  const matches = filterCommands(commands, ui.paletteInput.value);
  const selected = matches[activeCommandIndex];
  if (selected) void selected.run();
}

ui.analysisButton.addEventListener("click", () => void toggleAnalysis());
required<HTMLButtonElement>("#command-trigger").addEventListener("click", () => openPalette());
required<HTMLButtonElement>("#goto-button").addEventListener("click", () => openPalette("address"));
required<HTMLButtonElement>("#menu-button").addEventListener("click", () => ui.sidebar.classList.add("open"));
required<HTMLButtonElement>("#sidebar-close").addEventListener("click", () => ui.sidebar.classList.remove("open"));
ui.paletteBackdrop.addEventListener("mousedown", (event) => { if (event.target === ui.paletteBackdrop) closePalette(); });
ui.paletteInput.addEventListener("input", () => { activeCommandIndex = 0; renderPalette(); });
ui.paletteInput.addEventListener("keydown", (event) => {
  const count = paletteMode === "commands" ? filterCommands(commands, ui.paletteInput.value).length : 1;
  if (event.key === "ArrowDown") { event.preventDefault(); activeCommandIndex = Math.min(count - 1, activeCommandIndex + 1); renderPalette(); }
  if (event.key === "ArrowUp") { event.preventDefault(); activeCommandIndex = Math.max(0, activeCommandIndex - 1); renderPalette(); }
  if (event.key === "Enter") { event.preventDefault(); runActivePaletteItem(); }
});

let symbolSearchTimer: number | undefined;
ui.symbolSearch.addEventListener("input", () => {
  window.clearTimeout(symbolSearchTimer);
  const query = ui.symbolSearch.value;
  const sequence = ++searchSequence;
  symbolSearchTimer = window.setTimeout(async () => {
    try {
      const result = await workbenchApi.searchSymbols(query);
      if (sequence === searchSequence) renderSymbols(result.symbols);
    } catch {
      if (snapshot && sequence === searchSequence) {
        const needle = query.toLocaleLowerCase();
        renderSymbols(snapshot.symbols.filter((symbol) => symbol.name.toLocaleLowerCase().includes(needle)));
      }
    }
  }, 140);
});

document.addEventListener("keydown", (event) => {
  const modifier = event.metaKey || event.ctrlKey;
  if (modifier && event.key.toLocaleLowerCase() === "k") { event.preventDefault(); openPalette(); }
  if (modifier && event.key.toLocaleLowerCase() === "g") { event.preventDefault(); openPalette("address"); }
  if (event.key === "Escape") { closePalette(); ui.sidebar.classList.remove("open"); }
  if (event.key === "/" && document.activeElement?.tagName !== "INPUT") { event.preventDefault(); ui.symbolSearch.focus(); }
});

async function connect(): Promise<void> {
  const started = performance.now();
  try {
    const data = await workbenchApi.snapshot();
    renderSnapshot(data);
    updateConnection(true);
    ui.engineLatency.textContent = `127.0.0.1 · ${Math.round(performance.now() - started)} ms`;
    const events = workbenchApi.eventSource();
    events.addEventListener("analysis", (event) => {
      const state = JSON.parse((event as MessageEvent<string>).data) as AnalysisState;
      renderAnalysis(state);
      if (snapshot) snapshot.analysis = state;
      updateConnection(true);
    });
    events.addEventListener("engine", () => updateConnection(true));
    events.onerror = () => updateConnection(false);
  } catch (error) {
    updateConnection(false);
    ui.listing.innerHTML = `<div class="service-empty"><strong>JVM service unavailable</strong><span>Start the loopback engine, then reload this view.</span><code>cd server && ./gradlew run</code></div>`;
    ui.decompiler.innerHTML = `<div class="service-empty compact"><strong>Awaiting analysis engine</strong><span>The web shell is running independently.</span></div>`;
    showToast(error instanceof Error ? error.message : "Could not reach workbench service", "error");
  }
}

void connect();
