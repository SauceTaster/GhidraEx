import * as vscode from 'vscode';
import {
  capabilityReport,
  resolveBackendCapabilities,
  type BackendCapabilitySnapshot,
  type BackendMode,
} from './core/capabilities';
import {
  PROGRAM_ID,
  TOTAL_ROWS,
  SyntheticEngine,
  formatAddress,
  rowForAddress,
  type EvidenceRecord,
  type ReferenceMatch,
  type SymbolMatch,
} from './core/engine';
import {
  NativeReadCancelledError,
  NativeReadService,
  SyntheticReadTransport,
  type NativeReadActivity,
} from './core/readService';
import { ViewStateReducer, type ViewFreshness } from './core/viewState';
import {
  DECOMPILER_SCHEME,
  LISTING_WINDOW_ROWS,
  LISTING_SCHEME,
  NATIVE_COMMANDS,
  NATIVE_SEMANTIC_TOKENS_LEGEND,
  DecompilerDocumentProvider,
  ListingDocumentProvider,
  NativeCodeLensProvider,
  NativeDefinitionProvider,
  NativeDocumentSymbolProvider,
  NativeHoverProvider,
  NativeRangeSemanticTokensProvider,
  NativeReferenceProvider,
  SYNTHETIC_VIEW_CONTEXT,
  collectNativeDecorationRanges,
  locationRefForGlobalRow,
  makeDecompilerUri,
  makeListingUri,
  parseNativeDocumentUri,
} from './native/documents';
import { NativeAnalysisCoordinator, type NativeAnalysisState } from './native/analysis';
import { NativeEvidenceIntegration } from './native/evidence';
import { NativeAdvancedViews } from './native/advancedViews';
import { NativeDebugIntegration, type NativeDebugSnapshot } from './native/debugging';
import { NativeScriptingIntegration } from './native/scripting';
import { LatestNativeRead, NativeReadCancellationPool } from './native/asyncReads';
import {
  NativeTreeViews,
  type NativeAnalysisStatus,
  type NativeEvidence,
  type NativeNavigationRequest,
} from './native/treeViews';

const INITIAL_ROW = 448;
const NATIVE_SELECTOR: vscode.DocumentSelector = [
  { scheme: LISTING_SCHEME },
  { scheme: DECOMPILER_SCHEME },
];

interface SymbolQuickPickItem extends vscode.QuickPickItem {
  readonly symbol: SymbolMatch;
}

interface ReferenceQuickPickItem extends vscode.QuickPickItem {
  readonly row: number;
}

interface BackendQuickPickItem extends vscode.QuickPickItem {
  readonly mode: BackendMode;
}

interface ProgrammaticListingSelection {
  readonly uri: string;
  readonly globalRow: number;
  readonly navigationId: number;
}

interface ActiveNavigation {
  readonly row: number;
  readonly promise: Promise<void>;
}

const FRESHNESS_PRESENTATION: Readonly<Record<ViewFreshness, { readonly icon: string; readonly label: string }>> = {
  EMPTY: { icon: '$(circle-outline)', label: 'empty' },
  LOADING: { icon: '$(sync~spin)', label: 'loading' },
  CURRENT: { icon: '$(check)', label: 'current' },
  STALE: { icon: '$(history)', label: 'stale' },
  PARTIAL: { icon: '$(warning)', label: 'partial' },
  FAILED: { icon: '$(error)', label: 'failed' },
  RESYNCING: { icon: '$(sync~spin)', label: 'resyncing' },
};

function clampRow(value: number): number {
  return Math.max(0, Math.min(TOTAL_ROWS - 1, Math.trunc(value)));
}

function errorDetail(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function treeAnalysisStatus(state: NativeAnalysisState): NativeAnalysisStatus {
  return state.lifecycle === 'queued' ? 'running' : state.lifecycle;
}

function treeEvidence(records: readonly EvidenceRecord[]): readonly NativeEvidence[] {
  return records.map(record => ({
    id: record.id,
    kind: record.classification === 'fact' ? 'finding' : 'hypothesis',
    title: record.title,
    detail: record.detail,
    confidence: record.confidence,
    row: record.row,
    tags: record.classification === 'fact'
      ? ['agent-evidence', 'provenance-bearing']
      : ['agent-hypothesis', 'review-needed'],
  }));
}

class NativeWorkbenchController implements vscode.Disposable {
  private readonly engine = new SyntheticEngine();
  private readonly reads = new NativeReadService(
    new SyntheticReadTransport(this.engine),
    SYNTHETIC_VIEW_CONTEXT,
  );
  private readonly listingProvider = new ListingDocumentProvider(this.reads);
  private readonly decompilerProvider = new DecompilerDocumentProvider(this.reads);
  private readonly languageReadCancellations = new NativeReadCancellationPool();
  private readonly selectedMetadataRead = new LatestNativeRead();
  private readonly symbolCommandRead = new LatestNativeRead();
  private readonly referenceCommandRead = new LatestNativeRead();
  private readonly viewState = new ViewStateReducer(SYNTHETIC_VIEW_CONTEXT);
  private readonly output = vscode.window.createOutputChannel('GhidraEx', { log: true });
  private readonly analysisStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 90);
  private readonly locationStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 89);
  private readonly backendStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 86);
  private readonly readStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 87);
  private readonly analysis = new NativeAnalysisCoordinator(this.engine, this.analysisStatus, this.output);
  private readonly evidence = new NativeEvidenceIntegration(this.reads, this.listingProvider);
  private readonly views: NativeTreeViews;
  private readonly advancedViews: NativeAdvancedViews;
  private readonly debug: NativeDebugIntegration;
  private readonly scripting: NativeScriptingIntegration;
  private readonly decorations = {
    addresses: vscode.window.createTextEditorDecorationType({
      color: new vscode.ThemeColor('editorLineNumber.activeForeground'),
      fontWeight: '600',
    }),
    bytes: vscode.window.createTextEditorDecorationType({
      color: new vscode.ThemeColor('descriptionForeground'),
    }),
    controlFlow: vscode.window.createTextEditorDecorationType({
      color: new vscode.ThemeColor('symbolIcon.methodForeground'),
      fontWeight: 'bold',
    }),
    comments: vscode.window.createTextEditorDecorationType({
      color: new vscode.ThemeColor('editorCodeLens.foreground'),
      fontStyle: 'italic',
    }),
  };
  private readonly disposables: vscode.Disposable[] = [];
  private capabilitySnapshot: BackendCapabilitySnapshot;
  private selectedRow = INITIAL_ROW;
  private selectedMetadataRow = -1;
  private selectedMetadataPendingRow = -1;
  private selectedFunctionName = 'loading…';
  private navigationSequence = 0;
  private activeNavigation: ActiveNavigation | undefined;
  private selectionTimer: ReturnType<typeof setTimeout> | undefined;
  private programmaticSelection: ProgrammaticListingSelection | undefined;
  private disposed = false;

  public constructor(private readonly context: vscode.ExtensionContext) {
    this.viewState.registerView('listing', true);
    this.viewState.navigate(locationRefForGlobalRow(INITIAL_ROW));
    this.capabilitySnapshot = this.readCapabilities();
    this.analysisStatus.name = 'GhidraEx analysis';
    this.analysisStatus.text = '$(debug-alt) GhidraEx';
    this.analysisStatus.tooltip = 'Open the GhidraEx native workbench';
    this.analysisStatus.command = 'ghidraex.openWorkbench';
    this.analysisStatus.show();

    this.locationStatus.name = 'GhidraEx location';
    this.locationStatus.command = 'ghidraex.jumpToAddress';
    this.locationStatus.show();

    this.backendStatus.name = 'GhidraEx backend';
    this.backendStatus.command = 'ghidraex.capabilities.selectBackend';
    this.backendStatus.show();

    this.readStatus.name = 'GhidraEx async reads';
    this.readStatus.text = '$(database) GX reads current';
    this.readStatus.tooltip = 'Context-qualified async read caches are ready.';
    this.readStatus.show();

    this.views = new NativeTreeViews({
      onNavigate: request => this.navigateFromTree(request),
      onRefresh: () => this.refresh(),
      onStartAnalysis: () => this.startAnalysis(),
      onCancelAnalysis: operationId => this.analysis.cancel(operationId),
      onError: error => this.reportError(error),
    }, this.reads);

    const initialDebug: NativeDebugSnapshot = {
      state: 'idle',
      backend: 'synthetic',
      row: INITIAL_ROW,
      reason: 'No debug session',
      threadName: 'Synthetic trace thread',
      breakpoints: [],
      registers: [{ name: 'RIP', value: formatAddress(INITIAL_ROW) }],
      readState: 'stale',
      readDetail: 'No debug session',
    };
    this.advancedViews = new NativeAdvancedViews(
      initialDebug,
      { consoleOpen: false, running: false, history: [] },
      this.capabilitySnapshot,
    );
    this.debug = new NativeDebugIntegration(
      this.output,
      this.reads,
      () => this.capabilitySnapshot,
      {
        onSnapshot: snapshot => this.advancedViews.updateDebug(snapshot),
        onNavigate: row => this.navigateToRow(row),
        onError: error => this.reportError(error),
      },
      INITIAL_ROW,
    );
    this.scripting = new NativeScriptingIntegration(
      this.reads,
      () => this.selectedRow,
      () => this.capabilitySnapshot,
      {
        onNavigate: row => this.navigateToRow(row),
        onStartAnalysis: () => this.startAnalysis(),
        onSnapshot: snapshot => this.advancedViews.updateScripting(snapshot),
        onError: error => this.reportError(error),
      },
    );

    this.registerNativeSurfaces();
    this.registerCommands();
    this.updateCapabilitySurfaces();
    this.setSelectedRow(INITIAL_ROW);
    this.output.info('Native VS Code workbench activated with TreeViews, DAP debugging, terminal scripting, and no workbench webview.');
    void vscode.commands.executeCommand('setContext', 'ghidraex.hasProgram', true);
  }

  private registerNativeSurfaces(): void {
    this.disposables.push(
      this.reads,
      this.languageReadCancellations,
      this.selectedMetadataRead,
      this.symbolCommandRead,
      this.referenceCommandRead,
      this.listingProvider,
      this.decompilerProvider,
      this.output,
      this.analysisStatus,
      this.locationStatus,
      this.backendStatus,
      this.readStatus,
      this.analysis,
      this.evidence,
      this.views,
      this.advancedViews,
      this.debug,
      this.scripting,
      ...Object.values(this.decorations),
      vscode.workspace.registerTextDocumentContentProvider(LISTING_SCHEME, this.listingProvider),
      vscode.workspace.registerTextDocumentContentProvider(DECOMPILER_SCHEME, this.decompilerProvider),
      vscode.languages.registerDocumentSymbolProvider(
        NATIVE_SELECTOR,
        new NativeDocumentSymbolProvider(this.reads, this.listingProvider, this.languageReadCancellations),
      ),
      vscode.languages.registerHoverProvider(
        NATIVE_SELECTOR,
        new NativeHoverProvider(this.reads, this.languageReadCancellations),
      ),
      vscode.languages.registerDefinitionProvider(
        NATIVE_SELECTOR,
        new NativeDefinitionProvider(this.reads, this.languageReadCancellations),
      ),
      vscode.languages.registerReferenceProvider(
        NATIVE_SELECTOR,
        new NativeReferenceProvider(this.reads, this.listingProvider, this.languageReadCancellations),
      ),
      vscode.languages.registerCodeLensProvider(
        NATIVE_SELECTOR,
        new NativeCodeLensProvider(this.reads, this.listingProvider, this.languageReadCancellations),
      ),
      vscode.languages.registerDocumentRangeSemanticTokensProvider(
        NATIVE_SELECTOR,
        new NativeRangeSemanticTokensProvider(this.reads, this.listingProvider, this.languageReadCancellations),
        NATIVE_SEMANTIC_TOKENS_LEGEND,
      ),
      this.reads.onDidChangeActivity(activity => this.updateReadActivity(activity)),
      vscode.languages.registerCodeActionsProvider(
        { scheme: LISTING_SCHEME },
        this.evidence,
        { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] },
      ),
      vscode.window.onDidChangeActiveTextEditor(editor => {
        if (editor !== undefined) this.onActiveEditor(editor);
      }),
      vscode.window.onDidChangeTextEditorVisibleRanges(event => this.applyDecorations(event.textEditor)),
      vscode.window.onDidChangeTextEditorSelection(event => this.onSelectionChanged(event)),
      vscode.workspace.onDidChangeConfiguration(event => {
        if (event.affectsConfiguration('ghidraex.engine')) this.updateCapabilitySurfaces();
      }),
      vscode.workspace.onDidGrantWorkspaceTrust(() => this.updateCapabilitySurfaces()),
      this.analysis.onDidChangeState(state => {
        this.views.updateAnalysis({
          ...(state.operationId === undefined ? {} : { operationId: state.operationId }),
          status: treeAnalysisStatus(state),
          percent: state.percent,
          phase: state.phase,
          detail: state.detail,
        });
      }),
    );
  }

  private registerCommands(): void {
    const register = (command: string, callback: (...args: unknown[]) => unknown): void => {
      this.disposables.push(vscode.commands.registerCommand(command, (...args: unknown[]) => {
        try {
          const result = callback(...args);
          if (result instanceof Promise) void result.catch(error => this.reportError(error));
          return result;
        } catch (error) {
          this.reportError(error);
          return undefined;
        }
      }));
    };

    register('ghidraex.openWorkbench', () => this.openWorkbench());
    register('ghidraex.openListing', (argument?: unknown) => this.openListing(this.rowFromArgument(argument), false));
    register('ghidraex.openDecompiler', (argument?: unknown) => this.openDecompiler(this.rowFromArgument(argument), false));
    register(NATIVE_COMMANDS.openListingAt, (argument?: unknown) => this.openListing(this.rowFromArgument(argument), false));
    register(NATIVE_COMMANDS.openDecompiler, (argument?: unknown) => this.openDecompiler(this.rowFromArgument(argument), false));
    register('ghidraex.jumpToAddress', () => this.promptForAddress());
    register('ghidraex.searchSymbols', () => this.searchSymbols());
    register('ghidraex.startAnalysis', () => this.startAnalysis());
    register('ghidraex.cancelAnalysis', (operationId?: unknown) => {
      this.analysis.cancel(typeof operationId === 'string' ? operationId : undefined);
    });
    register('ghidraex.showXrefs', (argument?: unknown) => this.showReferences(this.rowFromArgument(argument)));
    register('ghidraex.explainSelection', (argument?: unknown) => this.explainSelection(this.rowFromArgument(argument)));
    register('ghidraex.refreshViews', () => this.refresh());
    register('ghidraex.openLocation', (argument?: unknown) => this.openLocation(argument));
    register('ghidraex.debug.start', () => this.debug.start(this.selectedRow));
    register('ghidraex.debug.stop', () => this.debug.stop());
    register('ghidraex.debug.continue', () => this.debug.continue());
    register('ghidraex.debug.pause', () => this.debug.pause());
    register('ghidraex.debug.showConsole', () => this.debug.showConsole());
    register('ghidraex.scripting.openConsole', () => this.scripting.openConsole());
    register('ghidraex.scripting.newScript', () => this.scripting.newScript());
    register('ghidraex.scripting.runActiveScript', () => this.scripting.runActiveScript());
    register('ghidraex.scripting.showOutput', () => this.scripting.showOutput());
    register('ghidraex.scripting.clearHistory', () => this.scripting.clearHistory());
    register('ghidraex.capabilities.selectBackend', () => this.selectBackend());
    register('ghidraex.capabilities.openSettings', () => this.openBackendSettings());
    register('ghidraex.capabilities.copyReport', () => this.copyCapabilityReport());
    register('ghidraex.capabilities.inspectPlugin', (argument?: unknown) => this.inspectPlugin(argument));
  }

  public async openWorkbench(): Promise<void> {
    await vscode.commands.executeCommand('workbench.view.extension.ghidraex');
    await this.navigateToRow(this.selectedRow);
    this.output.info(`Opened native workbench at ${formatAddress(this.selectedRow)}`);
  }

  private async openListing(
    row: number,
    preserveFocus: boolean,
    navigationId = ++this.navigationSequence,
  ): Promise<vscode.TextEditor> {
    const selected = clampRow(row);
    this.setSelectedRow(selected);
    const anchor = locationRefForGlobalRow(selected);
    const request = this.viewState.beginRequest('listing', anchor, LISTING_WINDOW_ROWS);
    this.updateViewStateSurface();
    try {
      let document = await vscode.workspace.openTextDocument(makeListingUri(selected));
      if (document.languageId !== 'ghidraex-listing') {
        document = await vscode.languages.setTextDocumentLanguage(document, 'ghidraex-listing');
      }
      const semanticRows = Array.from({ length: document.lineCount }, (_, editorLine) =>
        this.listingProvider.lineRef(document.uri, editorLine)?.semanticRow,
      );
      const projection = this.listingProvider.projection(document.uri);
      if (semanticRows.some(candidate => candidate === undefined)) {
        throw new Error('Listing projection has an incomplete semantic line map');
      }
      if (projection === undefined) throw new Error('Listing projection metadata is unavailable');
      const acceptance = this.viewState.complete({
        viewId: request.viewId,
        requestId: request.requestId,
        context: request.context,
        resultId: `vscode:${request.requestId}:${document.uri.toString()}`,
        anchor: request.anchor,
        rows: semanticRows.filter(candidate => candidate !== undefined),
        completeness: projection.completeness,
        warnings: projection.warnings,
      });
      this.updateViewStateSurface();
      if (acceptance !== 'ACCEPTED' || navigationId !== this.navigationSequence || this.disposed) {
        throw new vscode.CancellationError();
      }
      const editor = await vscode.window.showTextDocument(document, {
        viewColumn: vscode.ViewColumn.One,
        preserveFocus,
        preview: true,
      });
      if (navigationId !== this.navigationSequence || this.disposed) throw new vscode.CancellationError();
      const editorLine = this.listingProvider.editorLineForGlobalRow(document.uri, selected);
      if (editorLine === undefined) throw new Error(`Listing projection does not contain global row ${selected}`);
      const target = new vscode.Range(
        editorLine,
        0,
        editorLine,
        Math.min(10, document.lineAt(editorLine).text.length),
      );
      this.expectProgrammaticSelection(editor.document.uri, selected, navigationId);
      editor.selection = new vscode.Selection(target.start, target.end);
      editor.revealRange(target, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
      await this.evidence.publishDocument(document, selected);
      this.applyDecorations(editor);
      return editor;
    } catch (error) {
      this.viewState.fail('listing', request.requestId, errorDetail(error));
      this.updateViewStateSurface();
      throw error;
    }
  }

  private async openDecompiler(
    row: number,
    preserveFocus: boolean,
    navigationId?: number,
  ): Promise<vscode.TextEditor> {
    this.cancelSelectionSync();
    const selected = clampRow(row);
    let document = await vscode.workspace.openTextDocument(makeDecompilerUri(selected));
    if (document.languageId !== 'c') document = await vscode.languages.setTextDocumentLanguage(document, 'c');
    if (navigationId !== undefined && navigationId !== this.navigationSequence) throw new vscode.CancellationError();
    const editor = await vscode.window.showTextDocument(document, {
      viewColumn: vscode.ViewColumn.Beside,
      preserveFocus,
      preview: true,
    });
    if (navigationId !== undefined && navigationId !== this.navigationSequence) throw new vscode.CancellationError();
    this.applyDecorations(editor);
    return editor;
  }

  private async navigateFromTree(request: NativeNavigationRequest): Promise<void> {
    this.output.debug(`Native ${request.source} navigation: ${request.label} (${request.address})`);
    await this.navigateToRow(request.row);
  }

  private navigateToRow(row: number): Promise<void> {
    const selected = clampRow(row);
    if (this.activeNavigation?.row === selected) return this.activeNavigation.promise;
    const tracked = this.performNavigation(selected).finally(() => {
      if (this.activeNavigation?.promise === tracked) this.activeNavigation = undefined;
    });
    this.activeNavigation = { row: selected, promise: tracked };
    return tracked;
  }

  private async performNavigation(selected: number): Promise<void> {
    const navigationId = ++this.navigationSequence;
    this.setSelectedRow(selected);
    try {
      await this.openListing(selected, false, navigationId);
      if (navigationId !== this.navigationSequence) return;
      await this.openDecompiler(selected, true, navigationId);
    } catch (error) {
      if (error instanceof vscode.CancellationError && navigationId !== this.navigationSequence) return;
      throw error;
    }
  }

  private onActiveEditor(editor: vscode.TextEditor): void {
    const descriptor = parseNativeDocumentUri(editor.document.uri);
    if (descriptor?.kind === 'listing') {
      const row = this.listingProvider.lineRef(editor.document.uri, editor.selection.active.line)?.globalRow;
      if (row !== undefined) this.setSelectedRow(row);
      void this.evidence.publishDocument(editor.document, row).catch(error => this.reportError(error));
    } else if (descriptor?.kind === 'decompiler') {
      this.cancelSelectionSync();
    } else {
      this.cancelSelectionSync();
    }
    this.applyDecorations(editor);
  }

  private onSelectionChanged(event: vscode.TextEditorSelectionChangeEvent): void {
    const descriptor = parseNativeDocumentUri(event.textEditor.document.uri);
    if (descriptor?.kind !== 'listing') return;
    const row = this.listingProvider.lineRef(
      event.textEditor.document.uri,
      event.selections[0]?.active.line ?? -1,
    )?.globalRow;
    if (row === undefined) return;
    const expected = this.programmaticSelection;
    const belongsToProgrammaticDocument = expected !== undefined &&
      expected.uri === event.textEditor.document.uri.toString();
    if (belongsToProgrammaticDocument) {
      if (expected.navigationId === this.navigationSequence && expected.globalRow === row) {
        this.clearProgrammaticSelection();
        this.setSelectedRow(row);
      } else if (event.kind === vscode.TextEditorSelectionChangeKind.Mouse ||
          event.kind === vscode.TextEditorSelectionChangeKind.Keyboard) {
        this.clearProgrammaticSelection();
      } else {
        return;
      }
      if (expected.navigationId === this.navigationSequence && expected.globalRow === row) return;
    }
    this.clearProgrammaticSelection();
    this.navigationSequence += 1;
    const selection = new Set(event.selections.flatMap(candidate => {
      const line = this.listingProvider.lineRef(event.textEditor.document.uri, candidate.active.line);
      return line === undefined ? [] : [line.semanticRow.location];
    }));
    this.viewState.setSelection(selection);
    this.setSelectedRow(row);
    if (this.selectionTimer !== undefined) clearTimeout(this.selectionTimer);
    const navigationId = this.navigationSequence;
    this.selectionTimer = setTimeout(() => {
      this.selectionTimer = undefined;
      const active = vscode.window.activeTextEditor;
      const stillSelected = active?.document.uri.scheme === LISTING_SCHEME &&
        this.listingProvider.lineRef(active.document.uri, active.selection.active.line)?.globalRow === row;
      if (!this.disposed && stillSelected && navigationId === this.navigationSequence) {
        void this.openDecompiler(row, true, navigationId).catch(error => this.reportError(error));
      }
    }, 140);
  }

  private cancelSelectionSync(): void {
    if (this.selectionTimer === undefined) return;
    clearTimeout(this.selectionTimer);
    this.selectionTimer = undefined;
  }

  private expectProgrammaticSelection(uri: vscode.Uri, globalRow: number, navigationId: number): void {
    this.clearProgrammaticSelection();
    this.programmaticSelection = {
      uri: uri.toString(),
      globalRow,
      navigationId,
    };
  }

  private clearProgrammaticSelection(): void {
    this.programmaticSelection = undefined;
  }

  private setSelectedRow(row: number): void {
    this.selectedRow = clampRow(row);
    this.viewState.navigate(locationRefForGlobalRow(this.selectedRow));
    this.views.setActiveRow(this.selectedRow);
    if (this.selectedMetadataRow !== this.selectedRow && this.selectedMetadataPendingRow !== this.selectedRow) {
      this.selectedFunctionName = 'loading…';
      this.views.setEvidenceLoading(`Loading evidence for ${formatAddress(this.selectedRow)}…`);
      this.selectedMetadataPendingRow = this.selectedRow;
      void this.loadSelectedMetadata(this.selectedRow);
    }
    this.updateViewStateSurface();
  }

  private async loadSelectedMetadata(row: number): Promise<void> {
    const request = this.selectedMetadataRead.begin();
    try {
      const [decompiled, evidence] = await Promise.all([
        this.reads.decompile(this.reads.context, row, {
          signal: request.signal,
          scope: 'selected-location',
        }),
        this.reads.evidence(this.reads.context, row, 64, {
          signal: request.signal,
          scope: 'selected-location',
        }),
      ]);
      if (!this.selectedMetadataRead.accepts(request.id) || this.selectedRow !== row) return;
      this.selectedMetadataPendingRow = -1;
      this.selectedMetadataRow = row;
      this.selectedFunctionName = decompiled.value.functionName;
      this.views.setEvidence(
        treeEvidence(evidence.value),
        evidence.completeness === 'COMPLETE'
          ? undefined
          : evidence.warnings.join(' · ') || `Evidence result is ${evidence.completeness.toLowerCase()}`,
      );
      this.updateViewStateSurface();
    } catch (error) {
      if (error instanceof NativeReadCancelledError || request.signal.aborted) return;
      if (!this.selectedMetadataRead.accepts(request.id) || this.selectedRow !== row) return;
      this.selectedMetadataPendingRow = -1;
      this.selectedMetadataRow = -1;
      this.selectedFunctionName = 'metadata unavailable';
      this.views.setEvidenceError(error);
      this.updateViewStateSurface();
      this.reportError(error);
    }
  }

  private updateViewStateSurface(): void {
    const view = this.viewState.view('listing');
    const freshness = view?.freshness ?? 'EMPTY';
    const presentation = FRESHNESS_PRESENTATION[freshness];
    const address = formatAddress(this.selectedRow);
    const functionName = this.selectedFunctionName;
    const authority = this.viewState.authoritativeActionsAllowed ? 'enabled' : 'disabled';
    const detail = view?.detail.trim() || 'No bounded listing result';
    this.locationStatus.text = `${presentation.icon} ${address} · ${presentation.label}`;
    this.locationStatus.tooltip = [
      `${PROGRAM_ID} · ${functionName} · global row ${this.selectedRow.toLocaleString()}`,
      `Listing state: ${freshness}`,
      detail,
      `Authority-bearing actions: ${authority}`,
    ].join('\n');
    void vscode.commands.executeCommand('setContext', 'ghidraex.listingFreshness', freshness);
    void vscode.commands.executeCommand(
      'setContext',
      'ghidraex.authoritativeViewActions',
      this.viewState.authoritativeActionsAllowed,
    );
  }

  private updateReadActivity(activity: NativeReadActivity): void {
    if (activity.freshness === 'cancelled') return;
    const presentation = activity.freshness === 'loading'
      ? { icon: '$(sync~spin)', label: 'loading' }
      : activity.freshness === 'resyncing'
        ? { icon: '$(sync~spin)', label: 'resyncing' }
        : activity.freshness === 'partial'
          ? { icon: '$(warning)', label: 'partial' }
      : activity.freshness === 'stale'
          ? { icon: '$(history)', label: 'stale' }
          : activity.freshness === 'error'
            ? { icon: '$(error)', label: 'error' }
            : { icon: '$(database)', label: 'current' };
    this.readStatus.text = `${presentation.icon} GX reads ${presentation.label}`;
    this.readStatus.tooltip = [
      `${activity.kind} · ${activity.freshness}`,
      activity.detail,
      `${activity.context.runtimeId}/${activity.context.programId}`,
      `epoch ${activity.context.runtimeEpoch} · generation ${activity.context.contentGeneration}`,
    ].join('\n');
    void vscode.commands.executeCommand('setContext', 'ghidraex.readFreshness', activity.freshness);
  }

  private applyDecorations(editor: vscode.TextEditor): void {
    if (parseNativeDocumentUri(editor.document.uri) === undefined) return;
    const ranges = collectNativeDecorationRanges(editor.document, editor.visibleRanges);
    editor.setDecorations(this.decorations.addresses, ranges.addresses);
    editor.setDecorations(this.decorations.bytes, ranges.bytes);
    editor.setDecorations(this.decorations.controlFlow, ranges.controlFlow);
    editor.setDecorations(this.decorations.comments, ranges.comments);
  }

  private async promptForAddress(): Promise<void> {
    const value = await vscode.window.showInputBox({
      title: 'GhidraEx: Go to Program Address',
      prompt: `Enter an aligned address in ${PROGRAM_ID}`,
      value: formatAddress(this.selectedRow),
      validateInput: input => rowForAddress(input) === undefined
        ? `Address must be aligned and between ${formatAddress(0)} and ${formatAddress(TOTAL_ROWS - 1)}.`
        : undefined,
    });
    if (value === undefined) return;
    const row = rowForAddress(value);
    if (row !== undefined) await this.navigateToRow(row);
  }

  private async searchSymbols(): Promise<void> {
    const request = this.symbolCommandRead.begin();
    let symbols: readonly SymbolMatch[];
    try {
      symbols = (await this.reads.symbols(this.reads.context, '', 50, {
        signal: request.signal,
        scope: 'symbol-quick-pick',
      })).value;
    } catch (error) {
      if (error instanceof NativeReadCancelledError || request.signal.aborted) return;
      throw error;
    }
    if (!this.symbolCommandRead.accepts(request.id)) return;
    const items: readonly SymbolQuickPickItem[] = symbols.map(symbol => ({
      label: `$(symbol-${symbol.kind === 'Function' ? 'method' : symbol.kind === 'Import' ? 'interface' : 'field'}) ${symbol.name}`,
      description: symbol.address,
      detail: `${symbol.kind} · ${Math.round(symbol.confidence * 100)}% confidence`,
      symbol,
    }));
    const selected = await vscode.window.showQuickPick(items, {
      title: 'GhidraEx: Search Symbols',
      placeHolder: 'Type to filter functions, imports, and labels',
      matchOnDescription: true,
      matchOnDetail: true,
    });
    if (selected !== undefined) await this.navigateToRow(selected.symbol.row);
  }

  private async showReferences(row: number): Promise<void> {
    const selectedRow = clampRow(row);
    const request = this.referenceCommandRead.begin();
    let references: readonly ReferenceMatch[];
    try {
      references = (await this.reads.references(this.reads.context, selectedRow, 256, {
        signal: request.signal,
        scope: 'reference-quick-pick',
      })).value;
    } catch (error) {
      if (error instanceof NativeReadCancelledError || request.signal.aborted) return;
      throw error;
    }
    if (!this.referenceCommandRead.accepts(request.id)) return;
    const items: readonly ReferenceQuickPickItem[] = references.map(reference => ({
      label: `$(references) ${reference.functionName}`,
      description: `${reference.kind} · ${reference.sourceAddress}`,
      detail: `Native reference to ${formatAddress(selectedRow)}`,
      row: reference.sourceRow,
    }));
    const selected = await vscode.window.showQuickPick(items, {
      title: `References to ${formatAddress(selectedRow)}`,
      placeHolder: 'Choose a reference to navigate in the native listing',
      matchOnDescription: true,
      matchOnDetail: true,
    });
    if (selected !== undefined) await this.navigateToRow(selected.row);
  }

  private async explainSelection(row: number): Promise<void> {
    const listing = await this.openListing(clampRow(row), false);
    await this.evidence.showEvidenceThread(listing.document, clampRow(row));
    await vscode.commands.executeCommand('ghidraex.evidence.focus');
    this.output.info(`Published provenance-bearing evidence for ${formatAddress(clampRow(row))}`);
  }

  private async startAnalysis(): Promise<void> {
    const lifecycle = await this.analysis.start();
    if (lifecycle === 'completed') {
      void vscode.window.showInformationMessage('GhidraEx analysis completed. Native views and diagnostics are up to date.');
    }
  }

  private async selectBackend(): Promise<void> {
    const modes: readonly BackendMode[] = ['synthetic', 'local', 'remote'];
    const items: readonly BackendQuickPickItem[] = modes.map(mode => {
      const candidate = this.readCapabilities(mode);
      return {
        label: `${mode === this.capabilitySnapshot.selectedMode ? '$(check) ' : ''}${candidate.backend.title}`,
        description: `${candidate.backend.state}${candidate.fallbackActive ? ' · synthetic fallback' : ''}`,
        detail: candidate.backend.detail,
        mode,
      };
    });
    const selected = await vscode.window.showQuickPick(items, {
      title: 'GhidraEx: Select Backend Contract',
      placeHolder: 'Unavailable modes remain visible so integration gaps are testable',
      matchOnDescription: true,
      matchOnDetail: true,
    });
    if (selected === undefined) return;
    const target = vscode.workspace.workspaceFolders === undefined
      ? vscode.ConfigurationTarget.Global
      : vscode.ConfigurationTarget.Workspace;
    await vscode.workspace.getConfiguration('ghidraex').update('engine.mode', selected.mode, target);
    this.updateCapabilitySurfaces();
    if (this.capabilitySnapshot.fallbackActive) {
      void vscode.window.showWarningMessage(
        `${this.capabilitySnapshot.backend.title}: ${this.capabilitySnapshot.backend.detail} Synthetic read-only surfaces remain active.`,
        'Open Backend Settings',
      ).then(action => {
        if (action === 'Open Backend Settings') void this.openBackendSettings();
      });
    }
  }

  private async openBackendSettings(): Promise<void> {
    await vscode.commands.executeCommand(
      'workbench.action.openSettings',
      '@ext:ghidraex.ghidraex-vscode-workbench ghidraex.engine',
    );
  }

  private async copyCapabilityReport(): Promise<void> {
    await vscode.env.clipboard.writeText(capabilityReport(this.capabilitySnapshot));
    void vscode.window.showInformationMessage('GhidraEx capability report copied.');
  }

  private async inspectPlugin(argument: unknown): Promise<void> {
    const id = typeof argument === 'string'
      ? argument
      : typeof argument === 'object' && argument !== null && 'plugin' in argument
        ? (argument as { readonly plugin?: { readonly id?: unknown } }).plugin?.id
        : undefined;
    const plugin = typeof id === 'string'
      ? this.capabilitySnapshot.plugins.find(candidate => candidate.id === id)
      : undefined;
    if (plugin === undefined) {
      await vscode.commands.executeCommand('ghidraex.capabilities.focus');
      return;
    }
    const action = await vscode.window.showInformationMessage(
      `${plugin.title} — ${plugin.state}: ${plugin.detail}`,
      'Open Backend Settings',
      'Copy Capability Report',
    );
    if (action === 'Open Backend Settings') await this.openBackendSettings();
    else if (action === 'Copy Capability Report') await this.copyCapabilityReport();
  }

  private readCapabilities(modeOverride?: BackendMode): BackendCapabilitySnapshot {
    // localPath/endpoint are resource-scoped so multi-root workspaces can choose independently.
    // This workbench projection follows the first open folder until a future per-program binding
    // supplies the owning resource explicitly; passing null keeps an empty window warning-free.
    const resource = vscode.workspace.workspaceFolders?.[0]?.uri ?? null;
    const windowConfiguration = vscode.workspace.getConfiguration('ghidraex', null);
    const resourceConfiguration = vscode.workspace.getConfiguration('ghidraex', resource);
    const configuredMode = windowConfiguration.get<string>('engine.mode', 'synthetic');
    const selectedMode: BackendMode = modeOverride ?? (
      configuredMode === 'local' || configuredMode === 'remote' ? configuredMode : 'synthetic'
    );
    return resolveBackendCapabilities({
      selectedMode,
      hostKind: vscode.env.uiKind === vscode.UIKind.Web ? 'web' : 'desktop',
      workspaceTrusted: vscode.workspace.isTrusted,
      localGhidraPath: resourceConfiguration.get<string>('engine.localPath', ''),
      remoteEndpoint: resourceConfiguration.get<string>('engine.endpoint', ''),
    });
  }

  private updateCapabilitySurfaces(): void {
    this.capabilitySnapshot = this.readCapabilities();
    this.advancedViews.updateCapabilities(this.capabilitySnapshot);
    const backend = this.capabilitySnapshot.backend;
    this.backendStatus.text = backend.state === 'ready'
      ? `$(server-environment) GX ${this.capabilitySnapshot.effectiveMode}`
      : `$(warning) GX ${this.capabilitySnapshot.selectedMode}`;
    this.backendStatus.tooltip = `${backend.title} · ${backend.state}\n${backend.detail}\nEffective backend: ${this.capabilitySnapshot.effectiveMode}`;
    void vscode.commands.executeCommand('setContext', 'ghidraex.backendMode', this.capabilitySnapshot.selectedMode);
    void vscode.commands.executeCommand('setContext', 'ghidraex.backendFallback', this.capabilitySnapshot.fallbackActive);
    void vscode.commands.executeCommand('setContext', 'ghidraex.realBackendReady', this.capabilitySnapshot.realBackendReady);
  }

  private async refresh(): Promise<void> {
    this.reads.invalidate('User requested a native view refresh');
    this.listingProvider.refresh();
    for (const document of vscode.workspace.textDocuments) {
      if (document.uri.scheme === DECOMPILER_SCHEME) this.decompilerProvider.refresh(document.uri);
    }
    this.updateCapabilitySurfaces();
    this.advancedViews.refreshAll();
    await this.views.refreshAll();
    this.output.info('Refreshed native project, symbol, analysis, evidence, debug, scripting, capability, and listing surfaces.');
  }

  private async openLocation(argument: unknown): Promise<void> {
    if (typeof argument === 'string') {
      const addressRow = rowForAddress(argument);
      if (addressRow !== undefined) {
        await this.navigateToRow(addressRow);
        return;
      }
    }
    await this.navigateToRow(this.rowFromArgument(argument));
  }

  private rowFromArgument(argument: unknown): number {
    if (typeof argument === 'number' && Number.isFinite(argument)) return clampRow(argument);
    if (typeof argument === 'object' && argument !== null && 'row' in argument) {
      const row = (argument as { readonly row?: unknown }).row;
      if (typeof row === 'number' && Number.isFinite(row)) return clampRow(row);
    }
    // Tree context-menu commands receive the selected provider element. Symbol
    // and evidence nodes intentionally wrap their engine records, while section
    // and detail nodes expose `row` directly.
    if (typeof argument === 'object' && argument !== null) {
      for (const key of ['symbol', 'evidence'] as const) {
        if (!(key in argument)) continue;
        const record = (argument as Partial<Record<typeof key, unknown>>)[key];
        if (typeof record === 'object' && record !== null && 'row' in record) {
          const row = (record as { readonly row?: unknown }).row;
          if (typeof row === 'number' && Number.isFinite(row)) return clampRow(row);
        }
      }
    }
    return this.selectedRow;
  }

  private reportError(error: unknown): void {
    if (error instanceof vscode.CancellationError) return;
    const detail = errorDetail(error);
    this.output.error(detail);
    void vscode.window.showErrorMessage(`GhidraEx: ${detail}`);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.navigationSequence += 1;
    this.cancelSelectionSync();
    this.clearProgrammaticSelection();
    this.viewState.dispose();
    void vscode.commands.executeCommand('setContext', 'ghidraex.hasProgram', false);
    void vscode.commands.executeCommand('setContext', 'ghidraex.backendFallback', false);
    void vscode.commands.executeCommand('setContext', 'ghidraex.realBackendReady', false);
    void vscode.commands.executeCommand('setContext', 'ghidraex.listingFreshness', 'EMPTY');
    void vscode.commands.executeCommand('setContext', 'ghidraex.authoritativeViewActions', false);
    void vscode.commands.executeCommand('setContext', 'ghidraex.readFreshness', 'stale');
    for (const disposable of [...this.disposables].reverse()) disposable.dispose();
  }
}

export function activate(context: vscode.ExtensionContext): void {
  const controller = new NativeWorkbenchController(context);
  context.subscriptions.push(controller);

  const openOnStartup = vscode.workspace.getConfiguration('ghidraex').get<boolean>('openOnStartup', false);
  if (openOnStartup) {
    const startupTimer = setTimeout(() => {
      void controller.openWorkbench().catch(error => {
        void vscode.window.showErrorMessage(`GhidraEx failed to open: ${errorDetail(error)}`);
      });
    }, 250);
    context.subscriptions.push({ dispose: () => clearTimeout(startupTimer) });
  }
}

export function deactivate(): void {}
