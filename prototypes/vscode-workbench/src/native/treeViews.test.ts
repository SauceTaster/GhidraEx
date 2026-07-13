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
    public accessibilityInformation?: unknown;

    public constructor(
      public label: string,
      public collapsibleState: number,
    ) {}
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
    commands: {},
  };
});

import { SyntheticEngine } from '../core/engine';
import {
  AnalysisTreeProvider,
  EvidenceTreeProvider,
  ProjectTreeProvider,
  SymbolsTreeProvider,
} from './treeViews';

describe('native tree providers', () => {
  it('exposes a project, program, and native memory sections', () => {
    const engine = new SyntheticEngine();
    const provider = new ProjectTreeProvider(engine.openProgram());
    const workspace = provider.getChildren()[0];
    expect(workspace?.kind).toBe('workspace');

    const program = provider.getChildren(workspace)[0];
    expect(program).toMatchObject({ kind: 'program', row: 0 });
    const sections = provider.getChildren(program);
    expect(sections.map(node => node.label)).toEqual(['.text', '.rodata', '.data']);
    expect(provider.getTreeItem(sections[0]!).contextValue).toBe('ghidraex.section');
    expect(provider.getTreeItem(sections[1]!).contextValue).toBe('ghidraex.sectionMetadata');
  });

  it('groups symbols and refreshes its native filter model', () => {
    const provider = new SymbolsTreeProvider(new SyntheticEngine());
    expect(provider.getChildren().map(node => node.kind)).toEqual(['group', 'group', 'group']);

    provider.setFilter('packet');
    const group = provider.getChildren()[0];
    expect(group).toMatchObject({ kind: 'group', label: 'Functions', count: 1 });
    expect(provider.getChildren(group)[0]).toMatchObject({
      kind: 'symbol',
      symbol: { name: 'decode_packet', row: 448 },
    });
  });

  it('maps analysis progress onto native pipeline states and actions', () => {
    const provider = new AnalysisTreeProvider();
    provider.update({
      operationId: 'analysis-1',
      status: 'running',
      percent: 57,
      phase: 'Resolving references',
      detail: 'Indexing cross-references',
    });

    const roots = provider.getChildren();
    expect(roots[0]).toMatchObject({ kind: 'summary', state: { percent: 57, status: 'running' } });
    expect(roots[1]).toMatchObject({ kind: 'action', action: 'cancel', operationId: 'analysis-1' });
    const pipeline = roots[2];
    expect(provider.getChildren(pipeline).map(node => node.kind === 'phase' ? node.state : undefined)).toEqual([
      'complete',
      'complete',
      'complete',
      'active',
      'pending',
    ]);
  });

  it('presents findings and hypotheses with navigable evidence locations', () => {
    const provider = new EvidenceTreeProvider();
    const roots = provider.getChildren();
    expect(roots).toHaveLength(2);
    expect(roots.map(node => node.kind === 'evidence' ? node.evidence.kind : undefined)).toEqual(['finding', 'hypothesis']);

    const first = roots[0];
    expect(provider.getChildren(first)[0]).toMatchObject({ kind: 'detail', row: 452, label: '0x00401710' });
  });
});
