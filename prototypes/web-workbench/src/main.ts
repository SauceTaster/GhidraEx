import "./style.css";
import { AdvancedWorkbenchController } from "./advanced";
import { workbenchApi } from "./api";
import { clampProgress, closestListingRow, filterCommands, normalizeAddress } from "./model";
import type { AnalysisState, Command, ListingRow, ScriptResult, SymbolRecord, WorkbenchSnapshot } from "./types";

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
          <button class="active" role="tab" aria-selected="true" data-workbench-view="code">Code</button>
          <button role="tab" aria-selected="false" data-workbench-view="debugger">Debugger</button>
          <button role="tab" aria-selected="false" data-workbench-view="scripting">Scripts</button>
          <button role="tab" aria-selected="false" data-workbench-view="extensions">Extensions</button>
        </div>
      </section>

      <div class="workspace-content">
      <div class="work-grid" id="code-workspace">
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

      <section class="advanced-workspace" id="advanced-workspace" hidden>
        <div class="advanced-view" id="debugger-workspace" hidden>
          <header class="advanced-toolbar">
            <div><strong>DEBUGGER COORDINATES</strong><span class="state-chip" id="debug-mode">CAPTURED TRACE</span><span class="state-chip" id="debug-status">PAUSED</span></div>
            <span class="coordinate-readout" id="debug-coordinates">snap 142 / 142</span>
            <div class="advanced-actions"><button id="debug-past">◀ Past snapshot</button><button id="debug-latest">Latest</button><button class="primary" id="debug-fork">Fork emulator</button><button id="debug-back" disabled>↶ Step</button><button id="debug-forward" disabled>Step ↷</button><button disabled title="Requires a connected Ghidra Trace RMI session">Attach live…</button></div>
          </header>
          <div class="debug-grid">
            <section><header><strong>CALL STACK</strong><span>Atomic thread/frame coordinate</span></header><div class="advanced-list" id="debug-stack" role="list" aria-label="Debugger call stack"></div></section>
            <section><header><strong>REGISTERS</strong><span>Stale values stay labeled</span></header><div class="advanced-list" id="debug-registers" role="list" aria-label="Debugger registers"></div></section>
            <section><header><strong>WATCHES</strong><span>Old evaluations are discarded</span></header><div class="advanced-list" id="debug-watches" role="list" aria-label="Debugger watches"></div></section>
          </div>
          <footer class="advanced-policy"><span>◉</span><strong>TARGET EFFECTS</strong><em id="debug-memory-state">Current captured memory</em><b id="debug-write-state">WRITES DISABLED</b><small>Trace edits are undoable · external target effects are not</small></footer>
        </div>

        <div class="advanced-view" id="scripting-workspace" hidden>
          <header class="advanced-toolbar">
            <div><strong>SCRIPTING & REPL</strong><span class="state-chip" id="script-status">READ-ONLY</span></div>
            <span class="coordinate-readout" id="script-backend">FIXTURE PREVIEW</span>
            <div class="advanced-actions"><button id="script-clear">Clear output</button><button class="primary" id="script-run">▶ Run read-only</button></div>
          </header>
          <div class="script-grid">
            <section><header><strong>EXACT SOURCE</strong><span>Review before execution</span></header><textarea id="script-source" aria-label="Script source" spellcheck="false">functions("packet")</textarea></section>
            <section><header><strong>OUTPUT</strong><span>Status and capability audit</span></header><pre id="script-output" aria-live="polite">GhidraEx script worker\nMode: read-only preview\nTry :backend or :where. Mutations become approval plans.</pre></section>
            <section><header><strong>SESSION HISTORY</strong><span>Double-click to restore</span></header><div class="advanced-list" id="script-history" role="list" aria-label="Script session history"></div></section>
          </div>
          <footer class="advanced-policy"><span>◇</span><strong>DECLARED CAPABILITIES</strong><div id="script-capabilities"><i>program.read</i></div><small>Read-only project mode is not a Java sandbox</small></footer>
        </div>

        <div class="advanced-view" id="extensions-workspace" hidden>
          <header class="advanced-toolbar">
            <div><strong>BACKEND & EXTENSIONS</strong><span class="state-chip" id="backend-health">NOT CONFIGURED</span></div>
            <span class="coordinate-readout" id="backend-version">Synthetic fixture engine</span>
          </header>
          <section class="backend-card">
            <div><strong>ISOLATED RUNTIME BOUNDARY</strong><span>Modern UI runtime and supported Ghidra runtime remain separate processes</span></div>
            <dl><div><dt>UI</dt><dd>JDK 25 / JavaFX 26 or browser</dd></div><div><dt>GHIDRA</dt><dd>JDK 21 headless sidecar</dd></div><div><dt>TRANSPORT</dt><dd>Immutable JSON documents</dd></div></dl>
            <code id="backend-launcher">Set GHIDRA_HOME to enable the Ghidra sidecar</code>
          </section>
          <section class="extension-inventory"><header><strong>INSTALLED COMPONENTS</strong><span id="extension-count">—</span></header><div id="extension-list" role="list" aria-label="Installed extensions"></div></section>
          <footer class="advanced-policy"><span>◇</span><strong>EXTENSION POLICY</strong><em>Program, filesystem, network, process, and live-debug capabilities are approved independently</em></footer>
        </div>
      </section>
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
  codeWorkspace: required<HTMLElement>("#code-workspace"),
  advancedWorkspace: required<HTMLElement>("#advanced-workspace"),
  debuggerWorkspace: required<HTMLElement>("#debugger-workspace"),
  scriptingWorkspace: required<HTMLElement>("#scripting-workspace"),
  extensionsWorkspace: required<HTMLElement>("#extensions-workspace"),
  debugMode: required<HTMLElement>("#debug-mode"),
  debugStatus: required<HTMLElement>("#debug-status"),
  debugCoordinates: required<HTMLElement>("#debug-coordinates"),
  debugStack: required<HTMLElement>("#debug-stack"),
  debugRegisters: required<HTMLElement>("#debug-registers"),
  debugWatches: required<HTMLElement>("#debug-watches"),
  debugMemoryState: required<HTMLElement>("#debug-memory-state"),
  debugWriteState: required<HTMLElement>("#debug-write-state"),
  debugPast: required<HTMLButtonElement>("#debug-past"),
  debugLatest: required<HTMLButtonElement>("#debug-latest"),
  debugFork: required<HTMLButtonElement>("#debug-fork"),
  debugBack: required<HTMLButtonElement>("#debug-back"),
  debugForward: required<HTMLButtonElement>("#debug-forward"),
  scriptSource: required<HTMLTextAreaElement>("#script-source"),
  scriptOutput: required<HTMLElement>("#script-output"),
  scriptStatus: required<HTMLElement>("#script-status"),
  scriptBackend: required<HTMLElement>("#script-backend"),
  scriptCapabilities: required<HTMLElement>("#script-capabilities"),
  scriptHistory: required<HTMLElement>("#script-history"),
  backendHealth: required<HTMLElement>("#backend-health"),
  backendVersion: required<HTMLElement>("#backend-version"),
  backendLauncher: required<HTMLElement>("#backend-launcher"),
  extensionCount: required<HTMLElement>("#extension-count"),
  extensionList: required<HTMLElement>("#extension-list"),
};

function prepareRealEngineShell(): void {
  if (workbenchApi.mode !== "real") return;
  const targetIcon = required<HTMLElement>(".target-icon");
  const targetCopy = required<HTMLElement>(".target-copy");
  targetIcon.textContent = "—";
  child<HTMLElement>(targetCopy, "span").textContent = "ENGINE PROGRAM";
  child<HTMLElement>(targetCopy, "strong").textContent = "Awaiting session.ready";
  child<HTMLElement>(targetCopy, "small").textContent = "No program facts loaded";
  required<HTMLElement>(".project-nav").innerHTML = `
    <div class="nav-heading"><span>ENGINE SESSION</span><button aria-label="Project actions">•••</button></div>
    <button class="tree-item tree-parent expanded"><span class="chevron">⌄</span><span class="file-glyph root-glyph">◈</span>Authenticated gateway</button>
    <div class="tree-children"><button class="tree-item active"><span class="file-glyph binary-glyph">01</span><span>Awaiting program</span><em>READ</em></button></div>`;
  document.querySelectorAll<HTMLElement>(".breadcrumbs span, .breadcrumbs strong").forEach((element) => { element.textContent = "Awaiting engine"; });
  required<HTMLElement>(".binary-chip").textContent = "—";
  required<HTMLElement>("#binary-name").textContent = "No program loaded";
  required<HTMLElement>("#binary-meta").textContent = "Waiting for canonical program.summary";
  required<HTMLElement>("#current-address").textContent = "—";
  required<HTMLElement>("#signature").textContent = "Awaiting decompile.function";
  const targetFactValues = document.querySelectorAll<HTMLElement>(".target-facts dd");
  targetFactValues.forEach((element) => { element.textContent = "—"; });
  ui.scriptSource.disabled = true;
  required<HTMLButtonElement>("#script-run").disabled = true;
  ui.scriptOutput.textContent = "Scripting unavailable\nProtocol v1 advertised no script or REPL execution capability. No fixture commands will run under a real engine identity.";
  ui.scriptStatus.textContent = "UNAVAILABLE";
  ui.scriptBackend.textContent = "AWAITING NEGOTIATION";
  ui.analysisButton.title = "Analysis controls require explicitly advertised start and cancel methods";
}

prepareRealEngineShell();

type WorkspaceView = "code" | "debugger" | "scripting" | "extensions";
const advanced = new AdvancedWorkbenchController();
const scriptHistory: string[] = [];
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

function switchWorkspace(view: WorkspaceView): void {
  ui.codeWorkspace.hidden = view !== "code";
  ui.advancedWorkspace.hidden = view === "code";
  ui.debuggerWorkspace.hidden = view !== "debugger";
  ui.scriptingWorkspace.hidden = view !== "scripting";
  ui.extensionsWorkspace.hidden = view !== "extensions";
  document.querySelectorAll<HTMLButtonElement>("[data-workbench-view]").forEach((button) => {
    const selected = button.dataset.workbenchView === view;
    button.classList.toggle("active", selected);
    button.setAttribute("aria-selected", String(selected));
  });
  if (view === "debugger") renderDebugger();
  if (view === "extensions") renderExtensions();
}

function renderTextList(target: HTMLElement, values: string[]): void {
  target.replaceChildren();
  values.forEach((value) => {
    const row = document.createElement("div");
    row.textContent = value;
    target.append(row);
  });
}

function renderDebugger(): void {
  if (workbenchApi.mode === "real") {
    ui.debugMode.textContent = "NOT NEGOTIATED";
    ui.debugStatus.textContent = "UNAVAILABLE";
    ui.debugCoordinates.textContent = "No debug.trace or target-control capability";
    ui.debugMemoryState.textContent = "Protocol v1 program reads do not imply debugger authority";
    ui.debugWriteState.textContent = "WRITES DISABLED";
    ui.debugWriteState.dataset.tone = "safe";
    renderTextList(ui.debugStack, ["Debugger state unavailable for this engine session"]);
    renderTextList(ui.debugRegisters, ["No register capability advertised"]);
    renderTextList(ui.debugWatches, ["No watch-evaluation capability advertised"]);
    ui.debugPast.disabled = true;
    ui.debugLatest.disabled = true;
    ui.debugFork.disabled = true;
    ui.debugBack.disabled = true;
    ui.debugForward.disabled = true;
    return;
  }
  const state = advanced.debugger;
  ui.debugMode.textContent = state.mode.replaceAll("-", " ").toLocaleUpperCase();
  ui.debugStatus.textContent = state.status.replaceAll("-", " ").toLocaleUpperCase();
  ui.debugCoordinates.textContent = `snap ${state.snapshot} / ${state.latestSnapshot} · ${state.thread} · frame #${state.frame}`;
  ui.debugMemoryState.textContent = state.staleMemory
    ? "Historical snapshot · memory/register values may be stale"
    : "Current snapshot · mapped captured memory";
  ui.debugWriteState.textContent = state.writesEnabled ? "EMULATOR WRITES" : "WRITES DISABLED";
  ui.debugWriteState.dataset.tone = state.writesEnabled ? "warning" : "safe";
  renderTextList(ui.debugStack, state.stack);
  renderTextList(ui.debugRegisters, Object.entries(state.registers).map(([name, value]) => `${name.padEnd(8)}  ${value}`));
  renderTextList(ui.debugWatches, state.watches);
  const emulator = state.mode === "emulator";
  ui.debugPast.disabled = emulator || state.snapshot === 0;
  ui.debugLatest.disabled = emulator || state.snapshot === state.latestSnapshot;
  ui.debugFork.disabled = emulator;
  ui.debugBack.disabled = !emulator;
  ui.debugForward.disabled = !emulator;
}

function renderScriptResult(result: ScriptResult, source: string): void {
  const timestamp = new Date().toLocaleTimeString([], { hour12: false });
  ui.scriptOutput.textContent += `\n[${timestamp}] ${result.status.toLocaleUpperCase()}\n${result.output}\n`;
  ui.scriptStatus.textContent = result.status.replaceAll("-", " ").toLocaleUpperCase();
  ui.scriptStatus.dataset.tone = result.status === "success" ? "safe"
    : result.status === "blocked" ? "error" : "warning";
  ui.scriptCapabilities.replaceChildren();
  const capabilities = result.requiredCapabilities.length ? result.requiredCapabilities : ["none" as const];
  capabilities.forEach((capability) => {
    const chip = document.createElement("i");
    chip.textContent = capability;
    ui.scriptCapabilities.append(chip);
  });
  if (source) {
    scriptHistory.unshift(`${timestamp}  ${source.replaceAll("\n", " ")}`);
    ui.scriptHistory.replaceChildren();
    scriptHistory.forEach((item) => {
      const button = document.createElement("button");
      button.textContent = item;
      button.addEventListener("dblclick", () => {
        const split = item.indexOf("  ");
        ui.scriptSource.value = split < 0 ? item : item.slice(split + 2);
      });
      ui.scriptHistory.append(button);
    });
  }
}

function evaluateScript(): void {
  const source = ui.scriptSource.value.trim();
  if (workbenchApi.mode === "real") {
    renderScriptResult({
      status: "backend-unavailable",
      output: "The negotiated protocol v1 session does not advertise scripting or REPL execution. No code was executed.",
      requiredCapabilities: [],
    }, source);
    return;
  }
  renderScriptResult(advanced.evaluate(source), source);
}

function renderExtensions(): void {
  const backend = advanced.backend;
  ui.backendHealth.textContent = backend.health.replaceAll("-", " ").toLocaleUpperCase();
  ui.backendHealth.dataset.tone = backend.health === "detected" || backend.health === "connected" ? "safe" : "warning";
  ui.backendVersion.textContent = `${backend.mode} · ${backend.version}`;
  ui.backendLauncher.textContent = backend.launcher;
  ui.scriptBackend.textContent = backend.health === "connected"
    ? `GHIDRA ${backend.version}`
    : backend.health === "detected"
      ? `GHIDRA ${backend.version} DETECTED · FIXTURE PREVIEW`
      : "FIXTURE PREVIEW";
  const active = advanced.extensions.filter((extension) => extension.enabled).length;
  ui.extensionCount.textContent = `${active} active / ${advanced.extensions.length} installed`;
  ui.extensionList.replaceChildren();
  advanced.extensions.forEach((extension) => {
    const managedRealMode = workbenchApi.mode === "real";
    const unavailableInRealMode = managedRealMode && extension.id !== "ghidra-headless";
    const row = document.createElement("article");
    row.className = "extension-row";
    const icon = document.createElement("i");
    icon.textContent = extension.enabled ? "✓" : "○";
    icon.dataset.active = String(extension.enabled);
    const identity = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = extension.name;
    const provider = document.createElement("span");
    provider.textContent = extension.provider;
    identity.append(name, provider);
    const details = document.createElement("div");
    const capabilities = document.createElement("code");
    capabilities.textContent = extension.requiredCapabilities.join(" · ") || "No elevated capabilities";
    const status = document.createElement("small");
    status.textContent = unavailableInRealMode
      ? "Unavailable · capability not advertised by protocol v1"
      : managedRealMode ? "Connected · lifecycle owned by the engine supervisor" : extension.status;
    details.append(capabilities, status);
    const toggle = document.createElement("button");
    toggle.textContent = unavailableInRealMode ? "Unavailable" : managedRealMode ? "Supervisor managed" : extension.enabled ? "Disable" : extension.requiredCapabilities.length ? "Review & enable" : "Enable";
    toggle.className = extension.enabled || managedRealMode ? "" : "primary";
    toggle.disabled = managedRealMode;
    toggle.addEventListener("click", () => {
      const next = advanced.setExtensionEnabled(
        extension.id,
        !extension.enabled,
        extension.requiredCapabilities,
      );
      showToast(next.status, next.enabled || next.status === "Disabled" ? "normal" : "error");
      renderExtensions();
    });
    row.append(icon, identity, details, toggle);
    ui.extensionList.append(row);
  });
}

function updateConnection(connected: boolean, stale = false): void {
  ui.serviceStatus.textContent = connected ? "SERVICE ONLINE" : stale ? "ENGINE DATA STALE" : "SERVICE OFFLINE";
  ui.serviceStatus.parentElement?.classList.toggle("online", connected);
  ui.engineState.textContent = connected
    ? workbenchApi.mode === "real" ? "Ghidra engine ready" : "JVM engine ready"
    : stale ? "Read failure · stale view" : "Connection lost";
  ui.engineLatency.textContent = connected
    ? workbenchApi.mode === "real" ? "same-origin gateway · NDJSON" : "127.0.0.1 · SSE"
    : stale ? "Refresh required" : "Retrying…";
  ui.analysisButton.disabled = !connected
    || !workbenchApi.supports("analysis.start")
    || !workbenchApi.supports("analysis.cancel");
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
    child<HTMLElement>(button, "small").textContent = workbenchApi.mode === "real" ? symbol.address : symbol.address.replace("0x00", "");
    button.title = workbenchApi.mode === "real"
      ? `${symbol.namespace} · canonical engine address`
      : `${symbol.namespace} · ${symbol.refs} references · ${symbol.confidence}% confidence`;
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
  required<HTMLElement>("#listing-range").textContent = workbenchApi.mode === "real"
    ? `${rows.length} code units · ${totalInstructions.toLocaleString()} instructions`
    : `${rows.length} visible · ${totalInstructions.toLocaleString()} total`;
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
    ["STACK Δ", workbenchApi.mode === "real" ? "Unavailable" : `${info.stackDelta >= 0 ? "+" : ""}${info.stackDelta}`],
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
  provenance.innerHTML = `<i></i><span><b></b><small></small></span><em></em>`;
  child<HTMLElement>(provenance, "i").textContent = workbenchApi.mode === "real" ? "GX" : "AI";
  child<HTMLElement>(provenance, "b").textContent = workbenchApi.mode === "real" ? "TYPE SOURCE" : "TYPE PROVENANCE";
  child<HTMLElement>(provenance, "small").textContent = info.prototypeSource;
  child<HTMLElement>(provenance, "em").textContent = workbenchApi.mode === "real" ? "GHIDRA" : "98%";
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

function listingRowForAddress(rows: ListingRow[], requested: string): ListingRow | undefined {
  if (workbenchApi.mode === "real") {
    return rows.find((row) => row.address === requested) ?? rows[0];
  }
  return closestListingRow(rows, requested);
}

function navigationAddress(input: string): string | null {
  if (workbenchApi.mode !== "real") return normalizeAddress(input);
  const candidate = input.trim();
  return candidate.length >= 1 && candidate.length <= 128 ? candidate : null;
}

async function navigateTo(address: string, symbolName?: string): Promise<void> {
  if (!snapshot) return;
  let rows = snapshot.listing;
  try {
    const viewport = await workbenchApi.listing(address, 50);
    rows = viewport.rows;
    const realLocation = workbenchApi.mode === "real"
      ? await Promise.all([
        workbenchApi.decompile(rows[0]?.address ?? address),
        workbenchApi.references(rows[0]?.address ?? address),
      ])
      : null;
    snapshot.listing = rows;
    snapshot.listingInfo = {
      totalInstructions: workbenchApi.mode === "real" ? snapshot.listingInfo.totalInstructions : viewport.totalInstructions,
      minAddress: viewport.minAddress,
      maxAddress: viewport.maxAddress,
      viewportRows: rows.length,
    };
    renderListing(rows, snapshot.listingInfo.totalInstructions);
    if (realLocation) {
      const [decompiler, references] = realLocation;
      snapshot.decompiler = decompiler.source;
      snapshot.inspector = {
        ...snapshot.inspector,
        address: rows[0]?.address ?? address,
        functionName: decompiler.functionName,
        signature: decompiler.signature,
        xrefsIn: references.incoming,
        xrefsOut: references.outgoing,
      };
      const activeFunction = document.querySelector<HTMLElement>(".breadcrumbs strong");
      if (activeFunction) activeFunction.textContent = symbolName ?? decompiler.functionName;
      ui.signature.textContent = decompiler.signature;
      renderDecompiler(decompiler.source);
    }
  } catch (error) {
    if (workbenchApi.mode === "real") {
      updateConnection(false, true);
      showToast(error instanceof Error ? error.message : "Engine location read failed", "error");
      return;
    }
    showToast("Listing service unavailable; using the current viewport", "error");
  }
  const row = listingRowForAddress(rows, address);
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
  advanced.updateBackend(data.backend);
  if (workbenchApi.mode === "real") {
    const targetCopy = required<HTMLElement>(".target-copy");
    required<HTMLElement>(".target-icon").textContent = data.project.format.slice(0, 2).toLocaleUpperCase();
    child<HTMLElement>(targetCopy, "strong").textContent = data.project.binary;
    child<HTMLElement>(targetCopy, "small").textContent = `${data.project.format} · ${data.project.architecture}`;
    const activeProgram = required<HTMLElement>(".project-nav .tree-children .tree-item span:nth-of-type(2)");
    activeProgram.textContent = data.project.binary;
    const breadcrumbs = document.querySelectorAll<HTMLElement>(".breadcrumbs span, .breadcrumbs strong");
    breadcrumbs[0]!.textContent = "Engine session";
    breadcrumbs[1]!.textContent = data.project.binary;
    breadcrumbs[2]!.textContent = data.inspector.functionName;
    required<HTMLElement>(".binary-chip").textContent = data.project.format.slice(0, 8).toLocaleUpperCase();
    const labels = document.querySelectorAll<HTMLElement>(".target-facts dt");
    const values = document.querySelectorAll<HTMLElement>(".target-facts dd");
    labels[1]!.textContent = "MIN ADDRESS";
    values[0]!.textContent = data.project.imageBase;
    values[1]!.textContent = data.listingInfo.minAddress;
    values[2]!.textContent = data.analysis.functionsDiscovered.toLocaleString();
  }
  required<HTMLElement>("#binary-name").textContent = data.project.binary;
  required<HTMLElement>("#binary-meta").textContent = `${data.project.format} · ${data.project.architecture} · SHA ${data.project.sha256.slice(0, 12)}…`;
  required<HTMLElement>("#image-base").textContent = data.project.imageBase;
  ui.signature.textContent = data.inspector.signature;
  renderSymbols(data.symbols);
  renderListing(data.listing, data.listingInfo.totalInstructions);
  renderDecompiler(data.decompiler);
  renderAnalysis(data.analysis);
  renderDebugger();
  renderExtensions();
  const initial = listingRowForAddress(data.listing, data.inspector.address) ?? data.listing[0];
  if (initial) selectListingRow(initial);
}

async function toggleAnalysis(): Promise<void> {
  if (!snapshot) return;
  if (!workbenchApi.supports("analysis.start") || !workbenchApi.supports("analysis.cancel")) {
    showToast("Analysis controls were not advertised by this engine session", "error");
    return;
  }
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
  { id: "analysis", label: "Toggle auto analysis", detail: "Start or cancel the active analysis pipeline", keywords: ["scan", "analyzers", "cancel"], run: toggleAnalysis },
  { id: "debugger", label: "Open debugger coordinates", detail: "Captured trace, snapshots, and emulator controls", keywords: ["trace", "debug", "registers", "watches"], run: () => { closePalette(); switchWorkspace("debugger"); } },
  { id: "scripting", label: "Open scripting and REPL", detail: "Capability-gated script preview and session history", keywords: ["python", "java", "console", "repl"], run: () => { closePalette(); switchWorkspace("scripting"); } },
  { id: "extensions", label: "Open backend and extensions", detail: "Inspect Ghidra runtime and capability grants", keywords: ["plugins", "backend", "ghidra home"], run: () => { closePalette(); switchWorkspace("extensions"); } },
  { id: "symbols", label: "Focus symbol filter", detail: "Search functions, imports, and labels", shortcut: "/", keywords: ["search", "function"], run: () => { closePalette(); ui.symbolSearch.focus(); } },
  { id: "sidebar", label: "Toggle project explorer", detail: "Show or hide the project sidebar", keywords: ["files", "panel"], run: () => { closePalette(); ui.sidebar.classList.toggle("open"); } },
  { id: "entry", label: "Navigate to image base", detail: "Open the active program's first listing viewport", keywords: ["start", "main", "image base"], run: () => snapshot ? navigateTo(snapshot.project.imageBase, "image base") : undefined },
  { id: "copy", label: "Copy current address", detail: "Place the selected location on the clipboard", keywords: ["clipboard", "address"], run: async () => { await navigator.clipboard.writeText(selectedAddress); closePalette(); showToast(`${selectedAddress} copied`); } },
];

function renderPalette(): void {
  ui.paletteResults.replaceChildren();
  if (paletteMode === "address") {
    const normalized = navigationAddress(ui.paletteInput.value);
    const row = normalized && snapshot ? listingRowForAddress(snapshot.listing, normalized) : undefined;
    const result = document.createElement("button");
    result.className = `palette-row active ${normalized ? "" : "disabled"}`;
    result.innerHTML = `<i>↳</i><span><strong></strong><small></small></span><em>ENTER</em>`;
    child<HTMLElement>(result, "strong").textContent = normalized
      ? `Go to ${normalized}`
      : workbenchApi.mode === "real" ? "Enter a canonical engine address" : "Enter a hexadecimal address";
    child<HTMLElement>(result, "small").textContent = row
      ? `${workbenchApi.mode === "real" ? "Loaded engine location" : "Nearest loaded instruction"}: ${row.address}`
      : workbenchApi.mode === "real" ? "Paste a canonical address returned by the engine" : "Example: 0x004011d0";
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
  ui.paletteInput.placeholder = mode === "address"
    ? workbenchApi.mode === "real" ? "Enter canonical engine address…" : "Enter address (hex)…"
    : "Type a command…";
  ui.paletteInput.value = "";
  renderPalette();
  window.requestAnimationFrame(() => ui.paletteInput.focus());
}

function closePalette(): void {
  ui.paletteBackdrop.hidden = true;
}

function runActivePaletteItem(): void {
  if (paletteMode === "address") {
    const address = navigationAddress(ui.paletteInput.value);
    if (address) void navigateTo(address);
    return;
  }
  const matches = filterCommands(commands, ui.paletteInput.value);
  const selected = matches[activeCommandIndex];
  if (selected) void selected.run();
}

document.querySelectorAll<HTMLButtonElement>("[data-workbench-view]").forEach((button) => {
  button.addEventListener("click", () => switchWorkspace(button.dataset.workbenchView as WorkspaceView));
});
ui.debugPast.addEventListener("click", () => {
  advanced.selectSnapshot(Math.max(0, advanced.debugger.snapshot - 24));
  renderDebugger();
});
ui.debugLatest.addEventListener("click", () => {
  advanced.selectSnapshot(advanced.debugger.latestSnapshot);
  renderDebugger();
});
ui.debugFork.addEventListener("click", () => { advanced.forkEmulator(); renderDebugger(); });
ui.debugBack.addEventListener("click", () => { advanced.stepEmulator(-1); renderDebugger(); });
ui.debugForward.addEventListener("click", () => { advanced.stepEmulator(1); renderDebugger(); });
required<HTMLButtonElement>("#script-run").addEventListener("click", evaluateScript);
required<HTMLButtonElement>("#script-clear").addEventListener("click", () => { ui.scriptOutput.textContent = ""; });
ui.scriptSource.addEventListener("keydown", (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
    event.preventDefault();
    evaluateScript();
  }
});
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
    } catch (error) {
      if (workbenchApi.mode === "real") {
        if (sequence === searchSequence) {
          updateConnection(false, true);
          showToast(error instanceof Error ? error.message : "Engine symbol search failed", "error");
        }
      } else if (snapshot && sequence === searchSequence) {
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
  if (modifier && event.key === "1") { event.preventDefault(); switchWorkspace("code"); }
  if (modifier && event.key === "2") { event.preventDefault(); switchWorkspace("debugger"); }
  if (modifier && event.key === "3") { event.preventDefault(); switchWorkspace("scripting"); }
  if (modifier && event.key === "4") { event.preventDefault(); switchWorkspace("extensions"); }
  if (event.key === "Escape") { closePalette(); ui.sidebar.classList.remove("open"); }
  if (event.key === "/" && document.activeElement?.tagName !== "INPUT") { event.preventDefault(); ui.symbolSearch.focus(); }
});

async function connect(): Promise<void> {
  const started = performance.now();
  try {
    const data = await workbenchApi.snapshot();
    renderSnapshot(data);
    updateConnection(true);
    ui.engineLatency.textContent = workbenchApi.mode === "real"
      ? `same-origin gateway · ${Math.round(performance.now() - started)} ms`
      : `127.0.0.1 · ${Math.round(performance.now() - started)} ms`;
    workbenchApi.subscribe({
      analysis: (state) => {
        renderAnalysis(state);
        if (snapshot) snapshot.analysis = state;
        updateConnection(true);
      },
      engine: () => updateConnection(true),
      warning: (message) => showToast(message, "error"),
      stale: (error) => {
        updateConnection(false, true);
        showToast(error.message, "error");
      },
    });
  } catch (error) {
    updateConnection(false);
    ui.listing.innerHTML = workbenchApi.mode === "real"
      ? `<div class="service-empty"><strong>Ghidra engine unavailable</strong><span>Real mode requires session.ready, protocol negotiation, and canonical program facts. Fixture data will not be substituted.</span></div>`
      : `<div class="service-empty"><strong>JVM service unavailable</strong><span>Start the loopback engine, then reload this view.</span><code>cd server && ./gradlew run</code></div>`;
    ui.decompiler.innerHTML = workbenchApi.mode === "real"
      ? `<div class="service-empty compact"><strong>No real decompilation loaded</strong><span>Reconnect the authenticated engine gateway.</span></div>`
      : `<div class="service-empty compact"><strong>Awaiting analysis engine</strong><span>The web shell is running independently.</span></div>`;
    showToast(error instanceof Error ? error.message : "Could not reach workbench service", "error");
  }
}

void connect();
