import * as vscode from 'vscode';
import type {
  BackendCapabilitySnapshot,
  CapabilityRecord,
  PluginCapabilityRecord,
} from '../core/capabilities';
import type { ReplResult } from '../core/repl';
import { formatAddress } from '../core/engine';
import type { NativeDebugSnapshot } from './debugging';

export const ADVANCED_VIEW_IDS = {
  debug: 'ghidraex.debugSessions',
  scripting: 'ghidraex.scripting',
  capabilities: 'ghidraex.capabilities',
} as const;

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

type DebugAction = 'start' | 'continue' | 'pause' | 'stop' | 'console';

export type DebugTreeNode =
  | { readonly id: 'summary'; readonly kind: 'summary'; readonly snapshot: NativeDebugSnapshot }
  | { readonly id: 'actions'; readonly kind: 'group'; readonly group: 'actions'; readonly label: string }
  | { readonly id: 'target'; readonly kind: 'group'; readonly group: 'target'; readonly label: string }
  | { readonly id: 'thread'; readonly kind: 'group'; readonly group: 'thread'; readonly label: string }
  | { readonly id: 'breakpoints'; readonly kind: 'group'; readonly group: 'breakpoints'; readonly label: string }
  | { readonly id: string; readonly kind: 'action'; readonly action: DebugAction; readonly label: string }
  | { readonly id: string; readonly kind: 'detail'; readonly label: string; readonly description: string; readonly icon: string; readonly row?: number }
  | { readonly id: string; readonly kind: 'register'; readonly label: string; readonly value: string }
  | { readonly id: string; readonly kind: 'breakpoint'; readonly row: number };

const DEBUG_ACTIONS: Record<DebugAction, { readonly command: string; readonly label: string; readonly icon: string }> = {
  start: { command: 'ghidraex.debug.start', label: 'Start synthetic debug session', icon: 'debug-start' },
  continue: { command: 'ghidraex.debug.continue', label: 'Continue', icon: 'debug-continue' },
  pause: { command: 'ghidraex.debug.pause', label: 'Pause', icon: 'debug-pause' },
  stop: { command: 'ghidraex.debug.stop', label: 'Stop', icon: 'debug-stop' },
  console: { command: 'ghidraex.debug.showConsole', label: 'Open Debug Console', icon: 'debug-console' },
};

export class DebugSessionsTreeProvider extends RefreshableTreeProvider<DebugTreeNode> {
  public constructor(private snapshot: NativeDebugSnapshot) {
    super();
  }

  public get state(): NativeDebugSnapshot {
    return this.snapshot;
  }

  public update(snapshot: NativeDebugSnapshot): void {
    this.snapshot = snapshot;
    this.refresh();
  }

  public getTreeItem(node: DebugTreeNode): vscode.TreeItem {
    if (node.kind === 'summary') {
      const item = new vscode.TreeItem(
        node.snapshot.sessionName ?? 'No active debug session',
        vscode.TreeItemCollapsibleState.None,
      );
      item.id = 'ghidraex.debug.summary';
      item.description = `${node.snapshot.state} · reads ${node.snapshot.readState}`;
      item.tooltip = `${node.snapshot.reason}\n${node.snapshot.readDetail}\n${formatAddress(node.snapshot.row)}\nSynthetic trace only; no live process.`;
      item.contextValue = `ghidraex.debug.${node.snapshot.state}`;
      item.iconPath = new vscode.ThemeIcon(
        node.snapshot.state === 'stopped'
          ? 'debug-pause'
          : node.snapshot.state === 'running' || node.snapshot.state === 'starting'
            ? 'debug-continue'
            : node.snapshot.state === 'terminated' ? 'debug-stop' : 'debug-alt',
      );
      return item;
    }
    if (node.kind === 'group') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.Expanded);
      item.id = `ghidraex.debug.group.${node.group}`;
      item.contextValue = `ghidraex.debugGroup.${node.group}`;
      item.iconPath = new vscode.ThemeIcon(
        node.group === 'actions' ? 'run-all' : node.group === 'target' ? 'server' : node.group === 'thread' ? 'layers' : 'debug-breakpoint',
      );
      return item;
    }
    if (node.kind === 'action') {
      const descriptor = DEBUG_ACTIONS[node.action];
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.contextValue = `ghidraex.debugAction.${node.action}`;
      item.iconPath = new vscode.ThemeIcon(descriptor.icon);
      item.command = { command: descriptor.command, title: descriptor.label };
      return item;
    }
    if (node.kind === 'register') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.description = node.value;
      item.contextValue = 'ghidraex.debugRegister';
      item.iconPath = new vscode.ThemeIcon('symbol-number');
      return item;
    }
    if (node.kind === 'breakpoint') {
      const item = new vscode.TreeItem(formatAddress(node.row), vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.description = `row ${node.row.toLocaleString()}`;
      item.contextValue = 'ghidraex.debugLocation';
      item.iconPath = new vscode.ThemeIcon('debug-breakpoint');
      item.command = { command: 'ghidraex.openLocation', title: 'Open breakpoint', arguments: [node.row] };
      return item;
    }
    const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
    item.id = node.id;
    item.description = node.description;
    item.contextValue = node.row === undefined ? 'ghidraex.debugDetail' : 'ghidraex.debugLocation';
    item.iconPath = new vscode.ThemeIcon(node.icon);
    if (node.row !== undefined) {
      item.command = { command: 'ghidraex.openLocation', title: 'Open debug location', arguments: [node.row] };
    }
    return item;
  }

  public getChildren(node?: DebugTreeNode): DebugTreeNode[] {
    if (node === undefined) {
      return [
        { id: 'summary', kind: 'summary', snapshot: this.snapshot },
        { id: 'actions', kind: 'group', group: 'actions', label: 'Session controls' },
        { id: 'target', kind: 'group', group: 'target', label: 'Target and backend' },
        { id: 'thread', kind: 'group', group: 'thread', label: 'Thread, frame, and registers' },
        { id: 'breakpoints', kind: 'group', group: 'breakpoints', label: 'Breakpoints' },
      ];
    }
    if (node.kind !== 'group') return [];
    if (node.group === 'actions') return this.actionNodes();
    if (node.group === 'target') {
      return [
        {
          id: 'ghidraex.debug.target.backend',
          kind: 'detail',
          label: 'Synthetic trace adapter',
          description: 'No process / no TraceRmi',
          icon: 'warning',
        },
        {
          id: 'ghidraex.debug.target.location',
          kind: 'detail',
          label: formatAddress(this.snapshot.row),
          description: this.snapshot.reason,
          icon: 'location',
          row: this.snapshot.row,
        },
        {
          id: 'ghidraex.debug.target.reads',
          kind: 'detail',
          label: `Debug data: ${this.snapshot.readState}`,
          description: this.snapshot.readDetail,
          icon: this.snapshot.readState === 'loading'
            ? 'loading~spin'
            : this.snapshot.readState === 'current'
              ? 'pass'
              : this.snapshot.readState === 'partial' ? 'warning' : this.snapshot.readState === 'error' ? 'error' : 'history',
        },
      ];
    }
    if (node.group === 'thread') {
      return [
        {
          id: 'ghidraex.debug.thread.name',
          kind: 'detail',
          label: this.snapshot.threadName,
          description: this.snapshot.state,
          icon: 'debug-stackframe-active',
          row: this.snapshot.row,
        },
        ...this.snapshot.registers.map(register => ({
          id: `ghidraex.debug.register.${register.name.toLowerCase()}`,
          kind: 'register' as const,
          label: register.name,
          value: register.value,
        })),
      ];
    }
    if (this.snapshot.breakpoints.length === 0) {
      return [{
        id: 'ghidraex.debug.breakpoints.empty',
        kind: 'detail',
        label: 'No listing breakpoints',
        description: 'Use the editor gutter',
        icon: 'circle-slash',
      }];
    }
    return this.snapshot.breakpoints.map(row => ({
      id: `ghidraex.debug.breakpoint.${row}`,
      kind: 'breakpoint' as const,
      row,
    }));
  }

  private actionNodes(): DebugTreeNode[] {
    const state = this.snapshot.state;
    const actions: DebugAction[] = state === 'idle' || state === 'terminated'
      ? ['start']
      : state === 'stopped'
        ? ['continue', 'stop', 'console']
        : ['pause', 'stop', 'console'];
    return actions.map(action => ({
      id: `ghidraex.debug.action.${action}`,
      kind: 'action',
      action,
      label: DEBUG_ACTIONS[action].label,
    }));
  }
}

export interface NativeScriptingSnapshot {
  readonly consoleOpen: boolean;
  readonly running: boolean;
  readonly history: readonly ReplResult[];
}

export type ScriptingTreeNode =
  | { readonly id: 'runtime'; readonly kind: 'runtime'; readonly snapshot: NativeScriptingSnapshot }
  | { readonly id: string; readonly kind: 'group'; readonly group: 'actions' | 'languages' | 'history'; readonly label: string }
  | { readonly id: string; readonly kind: 'action'; readonly label: string; readonly command: string; readonly icon: string }
  | { readonly id: string; readonly kind: 'language'; readonly label: string; readonly state: 'ready' | 'requires-backend'; readonly detail: string }
  | { readonly id: string; readonly kind: 'history'; readonly result: ReplResult };

export class ScriptingTreeProvider extends RefreshableTreeProvider<ScriptingTreeNode> {
  public constructor(private snapshot: NativeScriptingSnapshot) {
    super();
  }

  public get state(): NativeScriptingSnapshot {
    return this.snapshot;
  }

  public update(update: Partial<NativeScriptingSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...update };
    this.refresh();
  }

  public getTreeItem(node: ScriptingTreeNode): vscode.TreeItem {
    if (node.kind === 'runtime') {
      const item = new vscode.TreeItem('GX bounded command runtime', vscode.TreeItemCollapsibleState.None);
      item.id = 'ghidraex.scripting.runtime';
      item.description = node.snapshot.running ? 'running' : node.snapshot.consoleOpen ? 'console open' : 'ready';
      item.tooltip = 'Synthetic-only command runtime. Python, Java, and GhidraScript are not executed without a real backend.';
      item.contextValue = 'ghidraex.scriptingRuntime.limited';
      item.iconPath = new vscode.ThemeIcon(node.snapshot.running ? 'loading~spin' : 'terminal');
      return item;
    }
    if (node.kind === 'group') {
      const item = new vscode.TreeItem(
        node.label,
        node.group === 'history' ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.Expanded,
      );
      item.id = node.id;
      item.contextValue = `ghidraex.scriptingGroup.${node.group}`;
      item.iconPath = new vscode.ThemeIcon(node.group === 'actions' ? 'run-all' : node.group === 'languages' ? 'code' : 'history');
      return item;
    }
    if (node.kind === 'action') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.contextValue = 'ghidraex.scriptingAction';
      item.iconPath = new vscode.ThemeIcon(node.icon);
      item.command = { command: node.command, title: node.label };
      return item;
    }
    if (node.kind === 'language') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.None);
      item.id = node.id;
      item.description = node.state === 'ready' ? 'ready' : 'real backend required';
      item.tooltip = node.detail;
      item.contextValue = `ghidraex.scriptLanguage.${node.state}`;
      item.iconPath = new vscode.ThemeIcon(node.state === 'ready' ? 'pass' : 'lock');
      return item;
    }
    const item = new vscode.TreeItem(node.result.input, vscode.TreeItemCollapsibleState.None);
    item.id = node.id;
    item.description = node.result.ok ? 'passed' : 'failed';
    item.tooltip = node.result.output.join('\n');
    item.contextValue = `ghidraex.scriptHistory.${node.result.ok ? 'passed' : 'failed'}`;
    item.iconPath = new vscode.ThemeIcon(node.result.ok ? 'check' : 'error');
    return item;
  }

  public getChildren(node?: ScriptingTreeNode): ScriptingTreeNode[] {
    if (node === undefined) {
      return [
        { id: 'runtime', kind: 'runtime', snapshot: this.snapshot },
        { id: 'ghidraex.scripting.actions', kind: 'group', group: 'actions', label: 'Script actions' },
        { id: 'ghidraex.scripting.languages', kind: 'group', group: 'languages', label: 'Runtime support' },
        { id: 'ghidraex.scripting.history', kind: 'group', group: 'history', label: `Recent commands (${this.snapshot.history.length})` },
      ];
    }
    if (node.kind !== 'group') return [];
    if (node.group === 'actions') {
      return [
        { id: 'ghidraex.scripting.action.console', kind: 'action', label: 'Open Script Console', command: 'ghidraex.scripting.openConsole', icon: 'terminal' },
        { id: 'ghidraex.scripting.action.new', kind: 'action', label: 'New Script', command: 'ghidraex.scripting.newScript', icon: 'new-file' },
        { id: 'ghidraex.scripting.action.run', kind: 'action', label: 'Run Active Script', command: 'ghidraex.scripting.runActiveScript', icon: 'run' },
        { id: 'ghidraex.scripting.action.output', kind: 'action', label: 'Show Script Output', command: 'ghidraex.scripting.showOutput', icon: 'output' },
      ];
    }
    if (node.group === 'languages') {
      return [
        { id: 'ghidraex.scripting.language.gx', kind: 'language', label: 'GX command script', state: 'ready', detail: 'Bounded, deterministic commands run in both desktop and browser extension hosts.' },
        { id: 'ghidraex.scripting.language.python', kind: 'language', label: 'Python / PyGhidra', state: 'requires-backend', detail: 'Requires a connected Ghidra JVM or authenticated remote service.' },
        { id: 'ghidraex.scripting.language.java', kind: 'language', label: 'Java GhidraScript', state: 'requires-backend', detail: 'Requires a connected Ghidra JVM with the Ghidra Script API.' },
      ];
    }
    return [...this.snapshot.history].reverse().slice(0, 20).map((result, index) => ({
      id: `ghidraex.scripting.history.${index}.${result.input}`,
      kind: 'history' as const,
      result,
    }));
  }
}

export type CapabilityTreeNode =
  | { readonly id: 'backend'; readonly kind: 'backend'; readonly snapshot: BackendCapabilitySnapshot }
  | { readonly id: string; readonly kind: 'group'; readonly group: 'capabilities' | 'plugins'; readonly label: string }
  | { readonly id: string; readonly kind: 'capability'; readonly capability: CapabilityRecord }
  | { readonly id: string; readonly kind: 'plugin'; readonly plugin: PluginCapabilityRecord };

function stateIcon(state: CapabilityRecord['state']): string {
  return state === 'ready' ? 'pass-filled' : state === 'limited' ? 'warning' : state === 'unconfigured' ? 'settings-gear' : 'error';
}

export class CapabilitiesTreeProvider extends RefreshableTreeProvider<CapabilityTreeNode> {
  public constructor(private snapshot: BackendCapabilitySnapshot) {
    super();
  }

  public get state(): BackendCapabilitySnapshot {
    return this.snapshot;
  }

  public update(snapshot: BackendCapabilitySnapshot): void {
    this.snapshot = snapshot;
    this.refresh();
  }

  public getTreeItem(node: CapabilityTreeNode): vscode.TreeItem {
    if (node.kind === 'backend') {
      const item = new vscode.TreeItem(node.snapshot.backend.title, vscode.TreeItemCollapsibleState.None);
      item.id = 'ghidraex.capabilities.backend';
      item.description = `${node.snapshot.backend.state} · requested ${node.snapshot.selectedMode}`;
      item.tooltip = `${node.snapshot.backend.detail}\nEffective backend: ${node.snapshot.effectiveMode}${node.snapshot.fallbackActive ? ' fallback' : ''}`;
      item.contextValue = `ghidraex.backend.${node.snapshot.backend.state}`;
      item.iconPath = new vscode.ThemeIcon(stateIcon(node.snapshot.backend.state));
      item.command = { command: 'ghidraex.capabilities.selectBackend', title: 'Select backend mode' };
      return item;
    }
    if (node.kind === 'group') {
      const item = new vscode.TreeItem(node.label, vscode.TreeItemCollapsibleState.Expanded);
      item.id = node.id;
      item.contextValue = `ghidraex.capabilityGroup.${node.group}`;
      item.iconPath = new vscode.ThemeIcon(node.group === 'plugins' ? 'extensions' : 'server-environment');
      return item;
    }
    const record = node.kind === 'plugin' ? node.plugin : node.capability;
    const item = new vscode.TreeItem(record.title, vscode.TreeItemCollapsibleState.None);
    item.id = node.id;
    item.description = record.state;
    item.tooltip = record.detail;
    item.contextValue = node.kind === 'plugin'
      ? `ghidraex.plugin.${record.state}`
      : `ghidraex.capability.${record.state}`;
    item.iconPath = new vscode.ThemeIcon(stateIcon(record.state));
    if (node.kind === 'plugin') {
      item.command = { command: 'ghidraex.capabilities.inspectPlugin', title: 'Inspect plugin capability', arguments: [node.plugin.id] };
    }
    return item;
  }

  public getChildren(node?: CapabilityTreeNode): CapabilityTreeNode[] {
    if (node === undefined) {
      return [
        { id: 'backend', kind: 'backend', snapshot: this.snapshot },
        { id: 'ghidraex.capabilities.group.features', kind: 'group', group: 'capabilities', label: 'Workbench capabilities' },
        { id: 'ghidraex.capabilities.group.plugins', kind: 'group', group: 'plugins', label: 'Plugins and bridges' },
      ];
    }
    if (node.kind !== 'group') return [];
    if (node.group === 'capabilities') {
      return this.snapshot.capabilities.map(capability => ({
        id: `ghidraex.capability.${capability.id}`,
        kind: 'capability' as const,
        capability,
      }));
    }
    return this.snapshot.plugins.map(plugin => ({
      id: `ghidraex.plugin.${plugin.id}`,
      kind: 'plugin' as const,
      plugin,
    }));
  }
}

export class NativeAdvancedViews implements vscode.Disposable {
  public readonly debug: DebugSessionsTreeProvider;
  public readonly scripting: ScriptingTreeProvider;
  public readonly capabilities: CapabilitiesTreeProvider;
  private readonly views: readonly vscode.TreeView<unknown>[];

  public constructor(
    debug: NativeDebugSnapshot,
    scripting: NativeScriptingSnapshot,
    capabilities: BackendCapabilitySnapshot,
  ) {
    this.debug = new DebugSessionsTreeProvider(debug);
    this.scripting = new ScriptingTreeProvider(scripting);
    this.capabilities = new CapabilitiesTreeProvider(capabilities);
    const debugView = vscode.window.createTreeView(ADVANCED_VIEW_IDS.debug, { treeDataProvider: this.debug, showCollapseAll: true });
    const scriptingView = vscode.window.createTreeView(ADVANCED_VIEW_IDS.scripting, { treeDataProvider: this.scripting, showCollapseAll: true });
    const capabilitiesView = vscode.window.createTreeView(ADVANCED_VIEW_IDS.capabilities, { treeDataProvider: this.capabilities, showCollapseAll: true });
    this.views = [debugView, scriptingView, capabilitiesView];
    this.updateDescriptions();
  }

  public updateDebug(snapshot: NativeDebugSnapshot): void {
    this.debug.update(snapshot);
    this.updateDescriptions();
  }

  public updateScripting(update: Partial<NativeScriptingSnapshot>): void {
    this.scripting.update(update);
    this.updateDescriptions();
  }

  public updateCapabilities(snapshot: BackendCapabilitySnapshot): void {
    this.capabilities.update(snapshot);
    this.updateDescriptions();
  }

  public refreshAll(): void {
    this.debug.refresh();
    this.scripting.refresh();
    this.capabilities.refresh();
  }

  private updateDescriptions(): void {
    const [debugView, scriptingView, capabilitiesView] = this.views ?? [];
    if (debugView !== undefined) debugView.description = this.debug.state.state;
    if (scriptingView !== undefined) scriptingView.description = this.scripting.state.consoleOpen ? 'console open' : 'bounded gx';
    if (capabilitiesView !== undefined) {
      capabilitiesView.description = this.capabilities.state.fallbackActive
        ? `${this.capabilities.state.selectedMode} → synthetic`
        : this.capabilities.state.effectiveMode;
    }
  }

  public dispose(): void {
    for (const view of [...this.views].reverse()) view.dispose();
    this.capabilities.dispose();
    this.scripting.dispose();
    this.debug.dispose();
  }
}
