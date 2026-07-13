import * as vscode from 'vscode';
import {
  PROGRAM_ID,
  TOTAL_ROWS,
  SyntheticEngine,
  formatAddress,
  rowForAddress,
  type EvidenceRecord,
  type SymbolMatch,
} from './core/engine';
import {
  DECOMPILER_SCHEME,
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
  collectNativeDecorationRanges,
  makeDecompilerUri,
  makeListingUri,
  parseNativeDocumentUri,
} from './native/documents';
import { NativeAnalysisCoordinator, type NativeAnalysisState } from './native/analysis';
import { NativeEvidenceIntegration } from './native/evidence';
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
  private readonly listingProvider = new ListingDocumentProvider(this.engine);
  private readonly decompilerProvider = new DecompilerDocumentProvider(this.engine);
  private readonly output = vscode.window.createOutputChannel('GhidraEx', { log: true });
  private readonly analysisStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 90);
  private readonly locationStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 89);
  private readonly analysis = new NativeAnalysisCoordinator(this.engine, this.analysisStatus, this.output);
  private readonly evidence = new NativeEvidenceIntegration(this.engine);
  private readonly views: NativeTreeViews;
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
  private selectedRow = INITIAL_ROW;
  private selectionTimer: ReturnType<typeof setTimeout> | undefined;
  private disposed = false;

  public constructor(private readonly context: vscode.ExtensionContext) {
    this.analysisStatus.name = 'GhidraEx analysis';
    this.analysisStatus.text = '$(debug-alt) GhidraEx';
    this.analysisStatus.tooltip = 'Open the GhidraEx native workbench';
    this.analysisStatus.command = 'ghidraex.openWorkbench';
    this.analysisStatus.show();

    this.locationStatus.name = 'GhidraEx location';
    this.locationStatus.command = 'ghidraex.jumpToAddress';
    this.locationStatus.show();

    this.views = new NativeTreeViews({
      onNavigate: request => this.navigateFromTree(request),
      onRefresh: () => this.refresh(),
      onStartAnalysis: () => this.startAnalysis(),
      onCancelAnalysis: operationId => this.analysis.cancel(operationId),
      onError: error => this.reportError(error),
    }, this.engine);

    this.registerNativeSurfaces();
    this.registerCommands();
    this.setSelectedRow(INITIAL_ROW);
    this.output.info('Native VS Code workbench activated; no workbench webview was created.');
    void vscode.commands.executeCommand('setContext', 'ghidraex.hasProgram', true);
  }

  private registerNativeSurfaces(): void {
    this.disposables.push(
      this.listingProvider,
      this.decompilerProvider,
      this.output,
      this.analysisStatus,
      this.locationStatus,
      this.analysis,
      this.evidence,
      this.views,
      ...Object.values(this.decorations),
      vscode.workspace.registerTextDocumentContentProvider(LISTING_SCHEME, this.listingProvider),
      vscode.workspace.registerTextDocumentContentProvider(DECOMPILER_SCHEME, this.decompilerProvider),
      vscode.languages.registerDocumentSymbolProvider(NATIVE_SELECTOR, new NativeDocumentSymbolProvider(this.engine)),
      vscode.languages.registerHoverProvider(NATIVE_SELECTOR, new NativeHoverProvider(this.engine)),
      vscode.languages.registerDefinitionProvider(NATIVE_SELECTOR, new NativeDefinitionProvider(this.engine)),
      vscode.languages.registerReferenceProvider(NATIVE_SELECTOR, new NativeReferenceProvider(this.engine)),
      vscode.languages.registerCodeLensProvider(NATIVE_SELECTOR, new NativeCodeLensProvider(this.engine)),
      vscode.languages.registerDocumentRangeSemanticTokensProvider(
        NATIVE_SELECTOR,
        new NativeRangeSemanticTokensProvider(this.engine),
        NATIVE_SEMANTIC_TOKENS_LEGEND,
      ),
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
  }

  public async openWorkbench(): Promise<void> {
    await vscode.commands.executeCommand('workbench.view.extension.ghidraex');
    await this.openListing(this.selectedRow, false);
    await this.openDecompiler(this.selectedRow, true);
    this.output.info(`Opened native workbench at ${formatAddress(this.selectedRow)}`);
  }

  private async openListing(row: number, preserveFocus: boolean): Promise<vscode.TextEditor> {
    const selected = clampRow(row);
    let document = await vscode.workspace.openTextDocument(makeListingUri());
    if (document.languageId !== 'ghidraex-listing') {
      document = await vscode.languages.setTextDocumentLanguage(document, 'ghidraex-listing');
    }
    const editor = await vscode.window.showTextDocument(document, {
      viewColumn: vscode.ViewColumn.One,
      preserveFocus,
      preview: false,
    });
    const target = new vscode.Range(selected, 0, selected, Math.min(10, document.lineAt(selected).text.length));
    editor.selection = new vscode.Selection(target.start, target.end);
    editor.revealRange(target, vscode.TextEditorRevealType.InCenterIfOutsideViewport);
    this.evidence.publishDocument(document);
    this.setSelectedRow(selected);
    this.applyDecorations(editor);
    return editor;
  }

  private async openDecompiler(row: number, preserveFocus: boolean): Promise<vscode.TextEditor> {
    this.cancelSelectionSync();
    const selected = clampRow(row);
    let document = await vscode.workspace.openTextDocument(makeDecompilerUri(selected));
    if (document.languageId !== 'c') document = await vscode.languages.setTextDocumentLanguage(document, 'c');
    const editor = await vscode.window.showTextDocument(document, {
      viewColumn: vscode.ViewColumn.Beside,
      preserveFocus,
      preview: true,
    });
    this.applyDecorations(editor);
    return editor;
  }

  private async navigateFromTree(request: NativeNavigationRequest): Promise<void> {
    this.output.debug(`Native ${request.source} navigation: ${request.label} (${request.address})`);
    await this.navigateToRow(request.row);
  }

  private async navigateToRow(row: number): Promise<void> {
    const selected = clampRow(row);
    this.setSelectedRow(selected);
    await this.openListing(selected, false);
    await this.openDecompiler(selected, true);
  }

  private onActiveEditor(editor: vscode.TextEditor): void {
    const descriptor = parseNativeDocumentUri(editor.document.uri);
    if (descriptor?.kind === 'listing') {
      this.setSelectedRow(editor.selection.active.line);
      this.evidence.publishDocument(editor.document);
    } else if (descriptor?.kind === 'decompiler') {
      this.cancelSelectionSync();
      this.setSelectedRow(descriptor.row);
    } else {
      this.cancelSelectionSync();
    }
    this.applyDecorations(editor);
  }

  private onSelectionChanged(event: vscode.TextEditorSelectionChangeEvent): void {
    const descriptor = parseNativeDocumentUri(event.textEditor.document.uri);
    if (descriptor?.kind !== 'listing') return;
    const row = clampRow(event.selections[0]?.active.line ?? this.selectedRow);
    this.setSelectedRow(row);
    if (this.selectionTimer !== undefined) clearTimeout(this.selectionTimer);
    this.selectionTimer = setTimeout(() => {
      this.selectionTimer = undefined;
      const active = vscode.window.activeTextEditor;
      const stillSelected = active?.document.uri.scheme === LISTING_SCHEME &&
        active.selection.active.line === row;
      if (!this.disposed && stillSelected) {
        void this.openDecompiler(row, true).catch(error => this.reportError(error));
      }
    }, 140);
  }

  private cancelSelectionSync(): void {
    if (this.selectionTimer === undefined) return;
    clearTimeout(this.selectionTimer);
    this.selectionTimer = undefined;
  }

  private setSelectedRow(row: number): void {
    this.selectedRow = clampRow(row);
    const address = formatAddress(this.selectedRow);
    const functionName = this.engine.decompile(this.selectedRow).functionName;
    this.locationStatus.text = `$(location) ${address}`;
    this.locationStatus.tooltip = `${PROGRAM_ID} · ${functionName} · row ${this.selectedRow.toLocaleString()}`;
    this.views.setActiveRow(this.selectedRow);
    this.views.setEvidence(treeEvidence(this.engine.evidenceFor(this.selectedRow)));
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
    const items: readonly SymbolQuickPickItem[] = this.engine.searchSymbols('', 50).map(symbol => ({
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
    const items: readonly ReferenceQuickPickItem[] = this.engine.referencesTo(clampRow(row)).map(reference => ({
      label: `$(references) ${reference.functionName}`,
      description: `${reference.kind} · ${reference.sourceAddress}`,
      detail: `Native reference to ${formatAddress(clampRow(row))}`,
      row: reference.sourceRow,
    }));
    const selected = await vscode.window.showQuickPick(items, {
      title: `References to ${formatAddress(clampRow(row))}`,
      placeHolder: 'Choose a reference to navigate in the native listing',
      matchOnDescription: true,
      matchOnDetail: true,
    });
    if (selected !== undefined) await this.navigateToRow(selected.row);
  }

  private async explainSelection(row: number): Promise<void> {
    const listing = await this.openListing(clampRow(row), false);
    this.evidence.showEvidenceThread(listing.document, clampRow(row));
    await vscode.commands.executeCommand('ghidraex.evidence.focus');
    this.output.info(`Published provenance-bearing evidence for ${formatAddress(clampRow(row))}`);
  }

  private async startAnalysis(): Promise<void> {
    const lifecycle = await this.analysis.start();
    if (lifecycle === 'completed') {
      void vscode.window.showInformationMessage('GhidraEx analysis completed. Native views and diagnostics are up to date.');
    }
  }

  private refresh(): void {
    this.listingProvider.refresh();
    this.views.refreshAll();
    this.output.info('Refreshed native project, symbol, analysis, evidence, and listing surfaces.');
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
    const detail = errorDetail(error);
    this.output.error(detail);
    void vscode.window.showErrorMessage(`GhidraEx: ${detail}`);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancelSelectionSync();
    void vscode.commands.executeCommand('setContext', 'ghidraex.hasProgram', false);
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
