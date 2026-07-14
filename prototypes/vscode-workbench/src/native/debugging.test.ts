import { describe, expect, it, vi } from 'vitest';

vi.mock('vscode', () => {
  class EventEmitter<T> {
    private listeners: ((value: T) => void)[] = [];
    public readonly event = (listener: (value: T) => void) => {
      this.listeners.push(listener);
      return { dispose: (): void => { this.listeners = this.listeners.filter(item => item !== listener); } };
    };
    public fire(value: T): void {
      for (const listener of this.listeners) listener(value);
    }
    public dispose(): void {
      this.listeners = [];
    }
  }
  return {
    EventEmitter,
    SemanticTokensLegend: class SemanticTokensLegend {
      public constructor(
        public readonly tokenTypes: readonly string[],
        public readonly tokenModifiers: readonly string[],
      ) {}
    },
    Uri: {
      from: ({ scheme, authority, path, query }: { scheme: string; authority: string; path: string; query: string }) => ({
        scheme,
        authority,
        path,
        query,
        toString: (): string => `${scheme}://${authority}${path}?${encodeURIComponent(query)}`,
      }),
      parse: (value: string) => {
        const parsed = new URL(value);
        const scheme = parsed.protocol.slice(0, -1);
        const authority = parsed.host;
        const path = parsed.pathname;
        const query = decodeURIComponent(parsed.search.slice(1));
        return { scheme, authority, path, query, toString: (): string => value };
      },
    },
    window: {},
    debug: {},
    commands: {},
    StatusBarAlignment: { Left: 1 },
    DebugConfigurationProviderTriggerKind: { Dynamic: 2 },
  };
});

import type * as vscode from 'vscode';
import { SyntheticEngine } from '../core/engine';
import { NativeReadService, SyntheticReadTransport } from '../core/readService';
import { SyntheticDebugAdapter, type NativeDebugAdapterOptions } from './debugging';
import { makeListingUri, SYNTHETIC_VIEW_CONTEXT } from './documents';

interface Message {
  readonly type: string;
  readonly command?: string;
  readonly event?: string;
  readonly success?: boolean;
  readonly message?: string;
  readonly body?: Record<string, unknown>;
}

function request(seq: number, command: string, args?: Record<string, unknown>): vscode.DebugProtocolMessage {
  return { seq, type: 'request', command, ...(args === undefined ? {} : { arguments: args }) } as vscode.DebugProtocolMessage;
}

function debugAdapter(options: Omit<NativeDebugAdapterOptions, 'reads'>): SyntheticDebugAdapter {
  const engine = new SyntheticEngine();
  const reads = new NativeReadService(new SyntheticReadTransport(engine), SYNTHETIC_VIEW_CONTEXT);
  return new SyntheticDebugAdapter({ ...options, reads });
}

describe('synthetic inline debug adapter', () => {
  it('negotiates DAP capabilities and states its synthetic limitation', () => {
    const adapter = debugAdapter({ initialRow: 448 });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    adapter.handleMessage(request(1, 'initialize'));
    adapter.handleMessage(request(2, 'launch'));
    expect(sent.find(message => message.command === 'initialize')?.body).toMatchObject({
      supportsConfigurationDoneRequest: true,
      supportsEvaluateForHovers: true,
    });
    expect(sent.find(message => message.event === 'output')?.body?.['output']).toContain('no process or Ghidra Debugger target');
    expect(sent.some(message => message.event === 'initialized')).toBe(true);
    adapter.dispose();
  });

  it('publishes a thread, native stack frames, scopes, and registers', async () => {
    const adapter = debugAdapter({ initialRow: 448 });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    adapter.handleMessage(request(1, 'threads'));
    await adapter.whenReadsSettled();
    adapter.handleMessage(request(2, 'stackTrace'));
    adapter.handleMessage(request(3, 'scopes'));
    adapter.handleMessage(request(4, 'variables', { variablesReference: 1 }));
    expect(sent.find(message => message.command === 'threads')?.body?.['threads']).toEqual([
      { id: 1, name: 'Synthetic trace thread' },
    ]);
    expect(sent.find(message => message.command === 'stackTrace')?.body?.['stackFrames']).toEqual(expect.arrayContaining([
      expect.objectContaining({
        name: 'decode_packet',
        line: 449,
        instructionPointerReference: '0x00401700',
        source: expect.objectContaining({
          path: expect.stringContaining('ghidraex-listing://runtime-local/orbit-controller-v1/ram/0.listing.asm'),
        }),
      }),
    ]));
    expect(sent.find(message => message.command === 'scopes')?.body?.['scopes']).toHaveLength(3);
    expect(sent.find(message => message.command === 'variables')?.body?.['variables']).toEqual(expect.arrayContaining([
      expect.objectContaining({ name: 'RIP', value: '0x00401700' }),
    ]));
    adapter.dispose();
  });

  it('tracks verified listing breakpoints and stops configuration at entry', async () => {
    const snapshots: { state: string; breakpoints: readonly number[] }[] = [];
    const adapter = debugAdapter({
      initialRow: 448,
      onSnapshot: snapshot => snapshots.push(snapshot),
    });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    adapter.handleMessage(request(1, 'setBreakpoints', { breakpoints: [{ line: 449 }, { line: 2_817 }] }));
    adapter.handleMessage(request(2, 'configurationDone'));
    await adapter.whenReadsSettled();
    expect(snapshots.at(-1)).toMatchObject({ state: 'stopped', breakpoints: [448, 2_816] });
    expect(sent.find(message => message.command === 'setBreakpoints')?.body?.['breakpoints']).toEqual(expect.arrayContaining([
      expect.objectContaining({ verified: true, line: 449 }),
    ]));
    expect(sent.some(message => message.event === 'stopped')).toBe(true);
    adapter.dispose();
  });

  it('accepts the legacy DAP lines array used by some extension hosts', () => {
    const snapshots: { breakpoints: readonly number[] }[] = [];
    const adapter = debugAdapter({
      initialRow: 448,
      onSnapshot: snapshot => snapshots.push(snapshot),
    });
    adapter.onDidSendMessage(() => undefined);
    adapter.handleMessage(request(1, 'setBreakpoints', { lines: [449, 2_817] }));
    expect(snapshots.at(-1)?.breakpoints).toEqual([448, 2_816]);
    adapter.dispose();
  });

  it('translates bounded-document lines to global breakpoint rows', () => {
    const snapshots: { breakpoints: readonly number[] }[] = [];
    const adapter = debugAdapter({
      initialRow: 8_192,
      onSnapshot: snapshot => snapshots.push(snapshot),
    });
    adapter.onDidSendMessage(() => undefined);
    adapter.handleMessage(request(1, 'setBreakpoints', {
      source: { path: makeListingUri(8_192).toString() },
      breakpoints: [{ line: 2 }],
    }));
    expect(snapshots.at(-1)?.breakpoints).toEqual([8_193]);
    adapter.dispose();
  });

  it('evaluates bounded debug-console expressions and rejects arbitrary ones', async () => {
    const adapter = debugAdapter({ initialRow: 448 });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    await adapter.whenReadsSettled();
    adapter.handleMessage(request(1, 'evaluate', { expression: 'pc' }));
    adapter.handleMessage(request(2, 'evaluate', { expression: 'decompile()' }));
    adapter.handleMessage(request(3, 'evaluate', { expression: 'globalThis' }));
    adapter.handleMessage(request(4, 'evaluate', { expression: 'x'.repeat(10_000) }));
    expect(sent.find(message => message.command === 'evaluate' && message.body?.['type'] === 'address')?.body?.['result']).toBe('0x00401700');
    expect(sent.find(message => message.command === 'evaluate' && message.body?.['type'] === 'decompiler text')?.body?.['result']).toContain('payloadLength');
    expect(sent.filter(message => message.command === 'evaluate').at(-1)).toMatchObject({ success: false });
    expect(sent.filter(message => message.command === 'evaluate').at(-1)?.message?.length).toBeLessThanOrEqual(2_048);
    adapter.dispose();
  });

  it('updates location on step and emits termination', async () => {
    const adapter = debugAdapter({ initialRow: 448 });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    adapter.handleMessage(request(1, 'next'));
    await adapter.whenReadsSettled();
    adapter.handleMessage(request(2, 'disconnect'));
    expect(sent.find(message => message.event === 'ghidraex.location')?.body).toMatchObject({ row: 449, reason: 'step' });
    expect(sent.some(message => message.event === 'terminated')).toBe(true);
    adapter.dispose();
  });

  it('continues to the next breakpoint on a bounded timer', async () => {
    vi.useFakeTimers();
    const adapter = debugAdapter({ initialRow: 448 });
    const sent: Message[] = [];
    adapter.onDidSendMessage(message => sent.push(message as Message));
    adapter.handleMessage(request(1, 'setBreakpoints', { breakpoints: [{ line: 701 }] }));
    adapter.handleMessage(request(2, 'continue'));
    expect(sent.some(message => message.event === 'continued')).toBe(true);
    await vi.runAllTimersAsync();
    expect(sent.find(message => message.event === 'ghidraex.location')?.body).toMatchObject({ row: 700, reason: 'breakpoint' });
    adapter.dispose();
    vi.useRealTimers();
  });
});
