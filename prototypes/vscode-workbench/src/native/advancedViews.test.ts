import { describe, expect, it, vi } from 'vitest';

vi.mock('vscode', () => {
  class EventEmitter<T> {
    public readonly event = () => ({ dispose(): void {} });
    public fire(_value: T): void {}
    public dispose(): void {}
  }
  class TreeItem {
    public id?: string;
    public description?: string | boolean;
    public tooltip?: string;
    public contextValue?: string;
    public iconPath?: unknown;
    public command?: unknown;
    public constructor(public label: string, public collapsibleState: number) {}
  }
  class ThemeIcon {
    public constructor(public readonly id: string) {}
  }
  return {
    EventEmitter,
    TreeItem,
    ThemeIcon,
    TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
    window: {},
  };
});

import { resolveBackendCapabilities } from '../core/capabilities';
import {
  CapabilitiesTreeProvider,
  DebugSessionsTreeProvider,
  ScriptingTreeProvider,
} from './advancedViews';

const debugSnapshot = {
  state: 'stopped' as const,
  backend: 'synthetic' as const,
  row: 448,
  reason: 'breakpoint',
  threadName: 'Synthetic trace thread',
  breakpoints: [448, 2_816],
  registers: [{ name: 'RIP', value: '0x00401700' }],
  readState: 'current' as const,
  readDetail: 'Current for 0x00401700',
};

describe('advanced native TreeView providers', () => {
  it('maps stopped debug state to controls, target location, registers, and breakpoints', () => {
    const provider = new DebugSessionsTreeProvider(debugSnapshot);
    const roots = provider.getChildren();
    expect(roots.map(node => node.kind)).toEqual(['summary', 'group', 'group', 'group', 'group']);
    const actions = provider.getChildren(roots[1]);
    expect(actions.map(node => node.kind === 'action' ? node.action : undefined)).toEqual(['continue', 'stop', 'console']);
    const target = provider.getChildren(roots[2]);
    expect(target).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'detail', row: 448 })]));
    const thread = provider.getChildren(roots[3]);
    expect(thread).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'register', label: 'RIP' })]));
    const breakpoints = provider.getChildren(roots[4]);
    expect(breakpoints.map(node => node.kind === 'breakpoint' ? node.row : undefined)).toEqual([448, 2_816]);
  });

  it('switches debug actions between idle, running, and stopped states', () => {
    const provider = new DebugSessionsTreeProvider({ ...debugSnapshot, state: 'idle', reason: 'No session' });
    const actionGroup = provider.getChildren()[1];
    expect(provider.getChildren(actionGroup)).toEqual([expect.objectContaining({ action: 'start' })]);
    provider.update({ ...debugSnapshot, state: 'running', reason: 'continue' });
    expect(provider.getChildren(provider.getChildren()[1]).map(node => node.kind === 'action' ? node.action : undefined)).toEqual([
      'pause', 'stop', 'console',
    ]);
  });

  it('exposes a bounded command runtime while locking real Ghidra script languages', () => {
    const provider = new ScriptingTreeProvider({ consoleOpen: false, running: false, history: [] });
    const roots = provider.getChildren();
    const languages = provider.getChildren(roots[2]);
    expect(languages).toEqual([
      expect.objectContaining({ label: 'GX command script', state: 'ready' }),
      expect.objectContaining({ label: 'Python / PyGhidra', state: 'requires-backend' }),
      expect.objectContaining({ label: 'Java GhidraScript', state: 'requires-backend' }),
    ]);
    expect(provider.getChildren(roots[1])).toHaveLength(4);
  });

  it('renders newest REPL history first with success state', () => {
    const provider = new ScriptingTreeProvider({
      consoleOpen: true,
      running: false,
      history: [
        { input: 'program', ok: true, output: ['fixture'] },
        { input: 'unknown', ok: false, output: ['failure'] },
      ],
    });
    const history = provider.getChildren(provider.getChildren()[3]);
    expect(history.map(node => node.kind === 'history' ? node.result.input : undefined)).toEqual(['unknown', 'program']);
    expect(provider.getTreeItem(history[0]!).contextValue).toBe('ghidraex.scriptHistory.failed');
  });

  it('groups backend truth, workbench capabilities, and plugin/bridge states', () => {
    const snapshot = resolveBackendCapabilities({
      selectedMode: 'local',
      hostKind: 'desktop',
      workspaceTrusted: true,
    });
    const provider = new CapabilitiesTreeProvider(snapshot);
    const roots = provider.getChildren();
    expect(roots[0]).toMatchObject({ kind: 'backend', snapshot: { fallbackActive: true } });
    expect(provider.getChildren(roots[1])).toHaveLength(snapshot.capabilities.length);
    const plugins = provider.getChildren(roots[2]);
    expect(plugins).toHaveLength(snapshot.plugins.length);
    expect(plugins).toEqual(expect.arrayContaining([
      expect.objectContaining({
        kind: 'plugin',
        plugin: expect.objectContaining({ title: 'Ghidra JVM bridge', state: 'unconfigured' }),
      }),
    ]));
  });
});
