import * as vscode from 'vscode';
import {
  PROGRAM_ID,
  TOTAL_ROWS,
  SyntheticEngine,
  formatAddress,
  type ProgramSummary,
  type SymbolMatch,
} from '../core/engine';

export const NATIVE_VIEW_IDS = {
  project: 'ghidraex.project',
  symbols: 'ghidraex.symbols',
  analysis: 'ghidraex.analysis',
  evidence: 'ghidraex.evidence',
} as const;

export const NATIVE_COMMAND_IDS = {
  navigate: 'ghidraex.native.navigate',
  refresh: 'ghidraex.native.refresh',
  filterSymbols: 'ghidraex.native.filterSymbols',
  clearSymbolFilter: 'ghidraex.native.clearSymbolFilter',
  startAnalysis: 'ghidraex.native.startAnalysis',
  cancelAnalysis: 'ghidraex.native.cancelAnalysis',
} as const;

export type NativeNavigationSource = 'project' | 'symbols' | 'evidence' | 'command';

export interface NativeNavigationRequest {
  readonly row: number;
  readonly address: string;
  readonly label: string;
  readonly source: NativeNavigationSource;
}

export interface NativeTreeCallbacks {
  readonly onNavigate: (request: NativeNavigationRequest) => void | PromiseLike<void>;
  readonly onRefresh?: () => void | PromiseLike<void>;
  readonly onStartAnalysis?: () => void | PromiseLike<void>;
  readonly onCancelAnalysis?: (operationId?: string) => void | PromiseLike<void>;
  readonly onError?: (error: unknown) => void | PromiseLike<void>;
}

abstract class RefreshableTreeProvider<T> implements vscode.TreeDataProvider<T>, vscode.Disposable {
  private readonly changes = new vscode.EventEmitter<T | undefined | null | void>();

  public readonly onDidChangeTreeData = this.changes.event;

  public refresh(element?: T): void {
    this.changes.fire(element);
  }

  public abstract getTreeItem(element: T): vscode.TreeItem;
  public abstract getChildren(element?: T): vscode.ProviderResult<T[]>;

  public dispose(): void {
    this.changes.dispose();
  }
}

export type ProjectTreeNode =
  | { readonly id: 'workspace'; readonly kind: 'workspace'; readonly label: string }
  | { readonly id: 'program'; readonly kind: 'program'; readonly label: string; readonly summary: ProgramSummary; readonly row: number }
  | { readonly id: string; readonly kind: 'section'; readonly label: string; readonly description: string; readonly row?: number };

export class ProjectTreeProvider extends RefreshableTreeProvider<ProjectTreeNode> {
  private activeRow = -1;

  public constructor(private readonly summary: ProgramSummary) {
    super();
  }

  public getTreeItem(node: ProjectTreeNode): vscode.TreeItem {
    const expandable = node.kind === 'workspace' || node.kind === 'program';
    const item = new vscode.TreeItem(
      node.label,
      expandable
        ? (node.kind === 'workspace' ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed)
        : vscode.TreeItemCollapsibleState.None,
    );
    item.id = `ghidraex.project.${node.id}`;
    item.contextValue = node.kind === 'workspace'
      ? 'ghidraex.workspace'
      : node.kind === 'program'
        ? 'ghidraex.program'
        : node.row === undefined ? 'ghidraex.sectionMetadata' : 'ghidraex.section';
    item.iconPath = new vscode.ThemeIcon(
      node.kind === 'workspace' ? 'root-folder-opened' : node.kind === 'program' ? 'file-binary' : 'symbol-namespace',
    );

    if (node.kind === 'program') {
      item.description = `${node.summary.format} · ${node.summary.status.replace('-', ' ')}`;
      item.tooltip = `${node.summary.name}\n${node.summary.language}\n${node.summary.instructionCount.toLocaleString()} instructions`;
    } else if (node.kind === 'section') {
      item.description = node.description;
      item.tooltip = node.row === undefined ? node.description : `${node.description}\n${formatAddress(node.row)}`;
    }

    if ('row' in node && node.row === this.activeRow) {
      item.iconPath = new vscode.ThemeIcon('eye');
    }
    item.accessibilityInformation = { label: `${node.label}${item.description ? `, ${String(item.description)}` : ''}` };
    return item;
  }

  public getChildren(node?: ProjectTreeNode): ProjectTreeNode[] {
    if (node === undefined) {
      return [{ id: 'workspace', kind: 'workspace', label: 'ORBIT FIRMWARE' }];
    }
    if (node.kind === 'workspace') {
      return [{ id: 'program', kind: 'program', label: this.summary.name, summary: this.summary, row: 0 }];
    }
    if (node.kind === 'program') {
      return [
        { id: 'section.text', kind: 'section', label: '.text', description: '393 KB · executable', row: 0 },
        { id: 'section.rodata', kind: 'section', label: '.rodata', description: '48 KB · read-only' },
        { id: 'section.data', kind: 'section', label: '.data', description: '12 KB · writable' },
      ];
    }
    return [];
  }

  public setActiveRow(row: number): void {
    if (this.activeRow === row) return;
    this.activeRow = row;
    this.refresh();
  }
}

type SymbolGroupKind = SymbolMatch['kind'];

export type SymbolsTreeNode =
  | { readonly id: string; readonly kind: 'group'; readonly symbolKind: SymbolGroupKind; readonly label: string; readonly count: number }
  | { readonly id: string; readonly kind: 'symbol'; readonly symbol: SymbolMatch }
  | { readonly id: 'empty'; readonly kind: 'empty'; readonly label: string };

const SYMBOL_GROUPS: readonly { readonly kind: SymbolGroupKind; readonly label: string; readonly icon: string }[] = [
  { kind: 'Function', label: 'Functions', icon: 'symbol-method' },
  { kind: 'Import', label: 'Imports', icon: 'symbol-interface' },
  { kind: 'Label', label: 'Labels', icon: 'symbol-field' },
];

export class SymbolsTreeProvider extends RefreshableTreeProvider<SymbolsTreeNode> {
  private query = '';
  private matches: readonly SymbolMatch[];
  private activeRow = -1;

  public constructor(private readonly engine: SyntheticEngine) {
    super();
    this.matches = engine.searchSymbols('', 50);
  }

  public get filter(): string {
    return this.query;
  }

  public getTreeItem(node: SymbolsTreeNode): vscode.TreeItem {
    if (node.kind === 'empty') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = 'ghidraex.symbols.empty';
      item.contextValue = 'ghidraex.symbolEmpty';
      item.iconPath = new vscode.ThemeIcon('search-stop');
      return item;
    }

    if (node.kind === 'group') {
      const item = new vscode.TreeItem(
        node.label,
        this.query.length > 0 ? vscode.TreeItemCollapsibleState.Expanded : vscode.TreeItemCollapsibleState.Collapsed,
      );
      item.id = node.id;
      item.description = String(node.count);
      item.contextValue = 'ghidraex.symbolGroup';
      item.iconPath = new vscode.ThemeIcon(SYMBOL_GROUPS.find(group => group.kind === node.symbolKind)?.icon ?? 'symbol-misc');
      return item;
    }

    const { symbol } = node;
    const item = new vscode.TreeItem(symbol.name, vscode.TreeItemCollapsibleState.None);
    item.id = node.id;
    item.description = symbol.address;
    item.contextValue = 'ghidraex.symbol';
    item.iconPath = new vscode.ThemeIcon(
      symbol.row === this.activeRow
        ? 'eye'
        : symbol.kind === 'Function' ? 'symbol-method' : symbol.kind === 'Import' ? 'symbol-interface' : 'symbol-field',
    );
    item.tooltip = `${symbol.kind} ${symbol.name}\n${symbol.address}\nConfidence ${Math.round(symbol.confidence * 100)}%`;
    item.accessibilityInformation = { label: `${symbol.kind} ${symbol.name}, ${symbol.address}` };
    return item;
  }

  public getChildren(node?: SymbolsTreeNode): SymbolsTreeNode[] {
    if (node === undefined) {
      if (this.matches.length === 0) {
        return [{ id: 'empty', kind: 'empty', label: this.query ? `No symbols match “${this.query}”` : 'No symbols available' }];
      }
      return SYMBOL_GROUPS.flatMap(group => {
        const count = this.matches.filter(match => match.kind === group.kind).length;
        return count === 0 ? [] : [{
          id: `ghidraex.symbols.group.${group.kind.toLowerCase()}`,
          kind: 'group' as const,
          symbolKind: group.kind,
          label: group.label,
          count,
        }];
      });
    }
    if (node.kind !== 'group') return [];
    return this.matches
      .filter(match => match.kind === node.symbolKind)
      .map(symbol => ({
        id: `ghidraex.symbols.symbol.${symbol.kind.toLowerCase()}.${symbol.name}`,
        kind: 'symbol' as const,
        symbol,
      }));
  }

  public setFilter(query: string): void {
    const normalized = query.trim();
    if (normalized === this.query) return;
    this.query = normalized;
    this.matches = this.engine.searchSymbols(normalized, 50);
    this.refresh();
  }

  public setActiveRow(row: number): void {
    if (this.activeRow === row) return;
    this.activeRow = row;
    this.refresh();
  }
}

export type NativeAnalysisStatus = 'idle' | 'running' | 'cancelling' | 'completed' | 'cancelled' | 'failed';

export interface NativeAnalysisSnapshot {
  readonly operationId?: string;
  readonly status: NativeAnalysisStatus;
  readonly percent: number;
  readonly phase: string;
  readonly detail: string;
}

export type AnalysisTreeNode =
  | { readonly id: 'summary'; readonly kind: 'summary'; readonly state: NativeAnalysisSnapshot }
  | { readonly id: 'pipeline'; readonly kind: 'pipeline' }
  | { readonly id: string; readonly kind: 'phase'; readonly label: string; readonly state: 'pending' | 'active' | 'complete' }
  | { readonly id: 'action'; readonly kind: 'action'; readonly action: 'start' | 'cancel'; readonly operationId?: string };

const ANALYSIS_PHASES = [
  { label: 'Discover code', threshold: 8 },
  { label: 'Recover functions', threshold: 31 },
  { label: 'Resolve references', threshold: 57 },
  { label: 'Infer types', threshold: 79 },
  { label: 'Finalize model', threshold: 100 },
] as const;

export class AnalysisTreeProvider extends RefreshableTreeProvider<AnalysisTreeNode> {
  private snapshot: NativeAnalysisSnapshot = {
    status: 'idle',
    percent: 0,
    phase: 'Ready',
    detail: 'Run deterministic analyzers; cancellation preserves valid partial state.',
  };

  public get state(): NativeAnalysisSnapshot {
    return this.snapshot;
  }

  public getTreeItem(node: AnalysisTreeNode): vscode.TreeItem {
    if (node.kind === 'summary') {
      const item = new vscode.TreeItem(node.state.phase, vscode.TreeItemCollapsibleState.None);
      item.id = 'ghidraex.analysis.summary';
      item.description = `${Math.round(node.state.percent)}% · ${node.state.status}`;
      item.tooltip = `${node.state.phase}\n${node.state.detail}`;
      item.contextValue = `ghidraex.analysis.${node.state.status}`;
      item.iconPath = new vscode.ThemeIcon(
        node.state.status === 'running' || node.state.status === 'cancelling'
          ? 'sync'
          : node.state.status === 'completed' ? 'pass-filled' : node.state.status === 'failed' ? 'error' : 'pulse',
      );
      return item;
    }
    if (node.kind === 'pipeline') {
      const item = new vscode.TreeItem('Analyzer pipeline', vscode.TreeItemCollapsibleState.Expanded);
      item.id = 'ghidraex.analysis.pipeline';
      item.contextValue = 'ghidraex.analysis.pipeline';
      item.iconPath = new vscode.ThemeIcon('server-process');
      return item;
    }
    if (node.kind === 'phase') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.description = node.state;
      item.contextValue = `ghidraex.analysis.phase.${node.state}`;
      item.iconPath = new vscode.ThemeIcon(node.state === 'complete' ? 'pass' : node.state === 'active' ? 'sync' : 'circle-large-outline');
      return item;
    }

    const cancelling = node.action === 'cancel';
    const title = cancelling ? 'Cancel analysis' : 'Start analysis';
    const item = new vscode.TreeItem(title, vscode.TreeItemCollapsibleState.None);
    item.id = 'ghidraex.analysis.action';
    item.contextValue = `ghidraex.analysis.action.${node.action}`;
    item.iconPath = new vscode.ThemeIcon(cancelling ? 'debug-stop' : 'play');
    item.command = {
      command: cancelling ? NATIVE_COMMAND_IDS.cancelAnalysis : NATIVE_COMMAND_IDS.startAnalysis,
      title,
      ...(node.operationId === undefined ? {} : { arguments: [node.operationId] }),
    };
    return item;
  }

  public getChildren(node?: AnalysisTreeNode): AnalysisTreeNode[] {
    if (node === undefined) {
      const busy = this.snapshot.status === 'running' || this.snapshot.status === 'cancelling';
      return [
        { id: 'summary', kind: 'summary', state: this.snapshot },
        {
          id: 'action',
          kind: 'action',
          action: busy ? 'cancel' : 'start',
          ...(this.snapshot.operationId === undefined ? {} : { operationId: this.snapshot.operationId }),
        },
        { id: 'pipeline', kind: 'pipeline' },
      ];
    }
    if (node.kind !== 'pipeline') return [];
    return ANALYSIS_PHASES.map((phase, index) => {
      const previousThreshold = ANALYSIS_PHASES[index - 1]?.threshold ?? 0;
      const complete = this.snapshot.status === 'completed' || this.snapshot.percent >= phase.threshold;
      const active = !complete &&
        (this.snapshot.status === 'running' || this.snapshot.status === 'cancelling') &&
        this.snapshot.percent >= previousThreshold;
      return {
        id: `ghidraex.analysis.phase.${index}`,
        kind: 'phase' as const,
        label: phase.label,
        state: complete ? 'complete' as const : active ? 'active' as const : 'pending' as const,
      };
    });
  }

  public update(update: Partial<NativeAnalysisSnapshot>): void {
    const percent = update.percent === undefined
      ? this.snapshot.percent
      : Math.max(0, Math.min(100, Math.round(update.percent)));
    this.snapshot = { ...this.snapshot, ...update, percent };
    this.refresh();
  }
}

export interface NativeEvidence {
  readonly id: string;
  readonly kind: 'finding' | 'hypothesis';
  readonly title: string;
  readonly detail: string;
  readonly confidence: number;
  readonly row?: number;
  readonly tags?: readonly string[];
}

export type EvidenceTreeNode =
  | { readonly id: string; readonly kind: 'evidence'; readonly evidence: NativeEvidence }
  | { readonly id: string; readonly kind: 'detail'; readonly label: string; readonly description?: string; readonly row?: number };

const DEFAULT_EVIDENCE: readonly NativeEvidence[] = [
  {
    id: 'length-guard',
    kind: 'finding',
    title: 'Length guard dominates decode',
    detail: 'payloadLength is bounded before decode_payload is called.',
    confidence: 0.92,
    row: 452,
    tags: ['control-flow', 'bounds-check'],
  },
  {
    id: 'signedness',
    kind: 'hypothesis',
    title: 'Possible signedness issue',
    detail: 'Hypothesis only; the evidence is insufficient for a mutation proposal.',
    confidence: 0.68,
    row: 449,
    tags: ['data-flow', 'review-needed'],
  },
];

export class EvidenceTreeProvider extends RefreshableTreeProvider<EvidenceTreeNode> {
  private items: readonly NativeEvidence[] = DEFAULT_EVIDENCE;
  private activeRow = -1;

  public getTreeItem(node: EvidenceTreeNode): vscode.TreeItem {
    if (node.kind === 'evidence') {
      const { evidence } = node;
      const item = new vscode.TreeItem(evidence.title, vscode.TreeItemCollapsibleState.Collapsed);
      item.id = node.id;
      item.description = `${Math.round(evidence.confidence * 100)}% · ${evidence.kind}`;
      item.contextValue = 'ghidraex.evidence';
      item.iconPath = new vscode.ThemeIcon(evidence.row === this.activeRow ? 'eye' : evidence.kind === 'finding' ? 'verified' : 'beaker');
      item.tooltip = `${evidence.title}\n${evidence.detail}`;
      item.accessibilityInformation = { label: `${evidence.title}, ${item.description}` };
      return item;
    }
    const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
    item.id = node.id;
    item.description = node.description;
    item.contextValue = node.row === undefined ? 'ghidraex.evidenceDetail' : 'ghidraex.evidence';
    item.iconPath = new vscode.ThemeIcon(node.row === undefined ? 'note' : node.row === this.activeRow ? 'eye' : 'location');
    item.tooltip = node.description;
    return item;
  }

  public getChildren(node?: EvidenceTreeNode): EvidenceTreeNode[] {
    if (node === undefined) {
      return this.items.map(evidence => ({ id: `ghidraex.evidence.${evidence.id}`, kind: 'evidence', evidence }));
    }
    if (node.kind !== 'evidence') return [];
    const children: EvidenceTreeNode[] = [];
    if (node.evidence.row !== undefined) {
      children.push({
        id: `${node.id}.location`,
        kind: 'detail',
        label: formatAddress(node.evidence.row),
        description: 'Evidence location',
        row: node.evidence.row,
      });
    }
    children.push({ id: `${node.id}.detail`, kind: 'detail', label: node.evidence.detail });
    if (node.evidence.tags !== undefined && node.evidence.tags.length > 0) {
      children.push({ id: `${node.id}.tags`, kind: 'detail', label: node.evidence.tags.join(' · '), description: 'Tags' });
    }
    return children;
  }

  public setEvidence(evidence: readonly NativeEvidence[]): void {
    this.items = [...evidence];
    this.refresh();
  }

  public setActiveRow(row: number): void {
    if (this.activeRow === row) return;
    this.activeRow = row;
    this.refresh();
  }
}

export class NativeTreeViews implements vscode.Disposable {
  public readonly project: ProjectTreeProvider;
  public readonly symbols: SymbolsTreeProvider;
  public readonly analysis: AnalysisTreeProvider;
  public readonly evidence: EvidenceTreeProvider;
  public readonly views: {
    readonly project: vscode.TreeView<ProjectTreeNode>;
    readonly symbols: vscode.TreeView<SymbolsTreeNode>;
    readonly analysis: vscode.TreeView<AnalysisTreeNode>;
    readonly evidence: vscode.TreeView<EvidenceTreeNode>;
  };

  private readonly disposables: vscode.Disposable[] = [];

  public constructor(
    private readonly callbacks: NativeTreeCallbacks,
    engine = new SyntheticEngine(),
  ) {
    this.project = new ProjectTreeProvider(engine.openProgram());
    this.symbols = new SymbolsTreeProvider(engine);
    this.analysis = new AnalysisTreeProvider();
    this.evidence = new EvidenceTreeProvider();
    this.views = {
      project: vscode.window.createTreeView(NATIVE_VIEW_IDS.project, { treeDataProvider: this.project, showCollapseAll: true }),
      symbols: vscode.window.createTreeView(NATIVE_VIEW_IDS.symbols, { treeDataProvider: this.symbols, showCollapseAll: true }),
      analysis: vscode.window.createTreeView(NATIVE_VIEW_IDS.analysis, { treeDataProvider: this.analysis, showCollapseAll: true }),
      evidence: vscode.window.createTreeView(NATIVE_VIEW_IDS.evidence, { treeDataProvider: this.evidence, showCollapseAll: true }),
    };

    this.disposables.push(
      this.project,
      this.symbols,
      this.analysis,
      this.evidence,
      ...Object.values(this.views),
      this.views.project.onDidChangeSelection(event => {
        const node = event.selection[0];
        if (node !== undefined && 'row' in node && node.row !== undefined) {
          this.navigate({ row: node.row, address: formatAddress(node.row), label: node.label, source: 'project' });
        }
      }),
      this.views.symbols.onDidChangeSelection(event => {
        const node = event.selection[0];
        if (node?.kind === 'symbol') {
          this.navigate({ row: node.symbol.row, address: node.symbol.address, label: node.symbol.name, source: 'symbols' });
        }
      }),
      this.views.evidence.onDidChangeSelection(event => {
        const node = event.selection[0];
        if (node?.kind === 'evidence' && node.evidence.row !== undefined) {
          this.navigate({
            row: node.evidence.row,
            address: formatAddress(node.evidence.row),
            label: node.evidence.title,
            source: 'evidence',
          });
        } else if (node?.kind === 'detail' && node.row !== undefined) {
          this.navigate({ row: node.row, address: formatAddress(node.row), label: node.label, source: 'evidence' });
        }
      }),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.navigate, (argument: unknown) => this.navigateCommand(argument)),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.refresh, () => this.run(async () => {
        await this.callbacks.onRefresh?.();
        this.refreshAll();
      })),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.filterSymbols, () => this.run(() => this.promptForSymbolFilter())),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.clearSymbolFilter, () => this.setSymbolFilter('')),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.startAnalysis, () => this.run(() => this.callbacks.onStartAnalysis?.())),
      vscode.commands.registerCommand(NATIVE_COMMAND_IDS.cancelAnalysis, (operationId?: string) => {
        this.run(() => this.callbacks.onCancelAnalysis?.(operationId));
      }),
    );
  }

  public setActiveRow(row: number): void {
    const clamped = Math.max(0, Math.min(TOTAL_ROWS - 1, Math.trunc(row)));
    this.project.setActiveRow(clamped);
    this.symbols.setActiveRow(clamped);
    this.evidence.setActiveRow(clamped);
  }

  public setSymbolFilter(query: string): void {
    this.symbols.setFilter(query);
    this.views.symbols.description = query.trim() ? `Filter: ${query.trim()}` : undefined;
  }

  public updateAnalysis(update: Partial<NativeAnalysisSnapshot>): void {
    this.analysis.update(update);
    this.views.analysis.description = `${this.analysis.state.percent}% · ${this.analysis.state.status}`;
  }

  public setEvidence(evidence: readonly NativeEvidence[]): void {
    this.evidence.setEvidence(evidence);
  }

  public refreshAll(): void {
    this.project.refresh();
    this.symbols.refresh();
    this.analysis.refresh();
    this.evidence.refresh();
  }

  private navigate(request: NativeNavigationRequest): void {
    this.setActiveRow(request.row);
    this.run(() => this.callbacks.onNavigate(request));
  }

  private navigateCommand(argument: unknown): void {
    const row = typeof argument === 'number'
      ? argument
      : typeof argument === 'object' && argument !== null && 'row' in argument
        ? (argument as { readonly row?: unknown }).row
        : undefined;
    if (typeof row !== 'number' || !Number.isInteger(row) || row < 0 || row >= TOTAL_ROWS) {
      this.reportError(new RangeError('Native navigation requires a valid instruction row.'));
      return;
    }
    this.navigate({ row, address: formatAddress(row), label: formatAddress(row), source: 'command' });
  }

  private async promptForSymbolFilter(): Promise<void> {
    const query = await vscode.window.showInputBox({
      title: 'GhidraEx: Filter Symbols View',
      prompt: 'Filter functions, imports, and labels in the native Symbols view',
      value: this.symbols.filter,
    });
    if (query !== undefined) this.setSymbolFilter(query);
  }

  private run(action: () => void | PromiseLike<void> | undefined): void {
    void Promise.resolve().then(action).catch(error => this.reportError(error));
  }

  private reportError(error: unknown): void {
    if (this.callbacks.onError !== undefined) {
      void Promise.resolve(this.callbacks.onError(error)).catch(handlerError => {
        void vscode.window.showErrorMessage(`GhidraEx native view error: ${String(handlerError)}`);
      });
      return;
    }
    const detail = error instanceof Error ? error.message : String(error);
    void vscode.window.showErrorMessage(`GhidraEx native view error: ${detail}`);
  }

  public dispose(): void {
    for (const disposable of [...this.disposables].reverse()) disposable.dispose();
  }
}

export function registerNativeTreeViews(
  context: vscode.ExtensionContext,
  callbacks: NativeTreeCallbacks,
  engine?: SyntheticEngine,
): NativeTreeViews {
  const nativeViews = new NativeTreeViews(callbacks, engine);
  context.subscriptions.push(nativeViews);
  return nativeViews;
}

export const SYNTHETIC_PROGRAM_ID = PROGRAM_ID;
