import { describe, expect, it } from 'vitest';
import type {
  DecompileResult,
  EvidenceRecord,
  ListingPage,
  ProgramSummary,
  ReferenceMatch,
  SymbolMatch,
} from './engine';
import {
  NativeReadCancelledError,
  NativeReadIdentityError,
  NativeReadResyncError,
  NativeReadService,
  NativeReadSupersededError,
  nativeReadContextKey,
  type ContextualReadResult,
  type NativeReadTransport,
} from './readService';
import type { ViewContext } from './viewState';

const CONTEXT: ViewContext = {
  runtimeId: 'runtime-local',
  runtimeEpoch: 3,
  programId: 'orbit-controller-v1',
  contentGeneration: 17,
};

interface Deferred<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
  readonly reject: (reason: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((accept, deny) => {
    resolve = accept;
    reject = deny;
  });
  return { promise, resolve, reject };
}

function result<T>(
  value: T,
  context: ViewContext = CONTEXT,
  resultId = 'fake-result',
  completeness: ContextualReadResult<T>['completeness'] = 'COMPLETE',
  warnings: readonly string[] = [],
): ContextualReadResult<T> {
  return { context, resultId, value, completeness, warnings };
}

function summary(): ProgramSummary {
  return {
    id: CONTEXT.programId,
    name: 'orbit-controller-v1',
    format: 'ELF x86-64',
    language: 'x86:LE:64:default',
    revision: CONTEXT.contentGeneration,
    imageBase: '0x00401000',
    instructionCount: 100_000,
    status: 'partially-analyzed',
  };
}

function decompile(row: number): DecompileResult {
  return {
    functionName: `FUN_${row}`,
    signature: `int FUN_${row}(void)`,
    text: `int FUN_${row}(void) { return 0; }`,
    row,
    address: '0x00401000',
  };
}

class FakeTransport implements NativeReadTransport {
  public symbolReads = new Map<string, Deferred<ContextualReadResult<readonly SymbolMatch[]>>>();
  public symbolSignals = new Map<string, AbortSignal>();
  public decompileReads = 0;
  public decompileFailure: Error | undefined;
  public decompileResponse: ContextualReadResult<DecompileResult> | undefined;
  public evidenceResponse: ContextualReadResult<readonly EvidenceRecord[]> | undefined;

  public async program(_context: ViewContext, _signal: AbortSignal): Promise<ContextualReadResult<ProgramSummary>> {
    return result(summary());
  }

  public async listing(
    _context: ViewContext,
    start: number,
    _count: number,
    _signal: AbortSignal,
  ): Promise<ContextualReadResult<ListingPage>> {
    return result({ start, count: 0, total: 100_000, rows: [] });
  }

  public async symbols(
    _context: ViewContext,
    query: string,
    _limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly SymbolMatch[]>> {
    const pending = deferred<ContextualReadResult<readonly SymbolMatch[]>>();
    this.symbolReads.set(query, pending);
    this.symbolSignals.set(query, signal);
    return pending.promise;
  }

  public async decompile(
    _context: ViewContext,
    row: number,
    _signal: AbortSignal,
  ): Promise<ContextualReadResult<DecompileResult>> {
    this.decompileReads += 1;
    if (this.decompileFailure !== undefined) throw this.decompileFailure;
    if (this.decompileResponse !== undefined) return this.decompileResponse;
    return result(decompile(row), CONTEXT, `decompile-${row}-${this.decompileReads}`);
  }

  public async references(
    _context: ViewContext,
    _row: number,
    _limit: number,
    _signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly ReferenceMatch[]>> {
    return result([]);
  }

  public async evidence(
    _context: ViewContext,
    _row: number,
    _limit: number,
    _signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly EvidenceRecord[]>> {
    return this.evidenceResponse ?? result([]);
  }
}

describe('context-qualified async native reads', () => {
  it('accepts only the latest request in a logical provider scope', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    const oldRead = reads.symbols(CONTEXT, 'old', 10, { scope: 'symbols-tree' });
    const newRead = reads.symbols(CONTEXT, 'new', 10, { scope: 'symbols-tree' });
    await Promise.resolve();
    expect(transport.symbolSignals.get('old')?.aborted).toBe(true);

    transport.symbolReads.get('new')?.resolve(result([{
      name: 'new_symbol', kind: 'Function', row: 2, address: '0x00401008', confidence: 1,
    }], CONTEXT, 'new-result'));
    await expect(newRead).resolves.toMatchObject({ resultId: 'new-result' });
    transport.symbolReads.get('old')?.resolve(result([{
      name: 'old_symbol', kind: 'Function', row: 1, address: '0x00401004', confidence: 1,
    }], CONTEXT, 'old-result'));
    await expect(oldRead).rejects.toBeInstanceOf(NativeReadSupersededError);
    expect(reads.cacheCounts.symbols).toBe(1);
  });

  it('rejects a response whose runtime/program/generation identity changed in flight', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    const pending = reads.symbols(CONTEXT, 'identity', 10);
    await Promise.resolve();
    transport.symbolReads.get('identity')?.resolve(result([], { ...CONTEXT, contentGeneration: 18 }));

    await expect(pending).rejects.toBeInstanceOf(NativeReadIdentityError);
    expect(reads.cacheCounts.symbols).toBe(0);
  });

  it('does not cache a late result after cancellation or disposal', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    const controller = new AbortController();
    const cancelled = reads.symbols(CONTEXT, 'cancelled', 10, { signal: controller.signal });
    await Promise.resolve();
    controller.abort();
    transport.symbolReads.get('cancelled')?.resolve(result([]));
    await expect(cancelled).rejects.toBeInstanceOf(NativeReadCancelledError);

    const disposed = reads.symbols(CONTEXT, 'disposed', 10);
    await Promise.resolve();
    reads.dispose();
    transport.symbolReads.get('disposed')?.resolve(result([]));
    await expect(disposed).rejects.toBeInstanceOf(NativeReadCancelledError);
    expect(reads.cacheCounts.symbols).toBe(0);
  });

  it('bounds context-qualified caches with LRU eviction', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT, {
      program: 1, listing: 1, symbols: 1, decompiler: 2, references: 1, evidence: 1,
    });
    await reads.decompile(CONTEXT, 0);
    await reads.decompile(CONTEXT, 64);
    await reads.decompile(CONTEXT, 128);
    expect(reads.cacheCounts.decompiler).toBe(2);
    await reads.decompile(CONTEXT, 0);
    expect(transport.decompileReads).toBe(4);
  });

  it('reports transport failures as native read errors without poisoning the cache', async () => {
    const transport = new FakeTransport();
    transport.decompileFailure = new Error('remote decompiler unavailable');
    const reads = new NativeReadService(transport, CONTEXT);
    const activities: string[] = [];
    reads.onDidChangeActivity(activity => activities.push(`${activity.freshness}:${activity.detail}`));

    await expect(reads.decompile(CONTEXT, 448)).rejects.toThrow(/unavailable/);
    expect(activities).toEqual([
      'loading:Awaiting async transport',
      'error:remote decompiler unavailable',
    ]);
    expect(reads.cacheCounts.decompiler).toBe(0);
  });

  it('preserves partial/truncated truth and quarantines every read during resync', async () => {
    const transport = new FakeTransport();
    transport.decompileResponse = result(
      decompile(448),
      CONTEXT,
      'truncated-decompile',
      'TRUNCATED',
      ['token budget reached'],
    );
    const reads = new NativeReadService(transport, CONTEXT);
    const freshness: string[] = [];
    reads.onDidChangeActivity(activity => freshness.push(activity.freshness));

    const truncated = await reads.decompile(CONTEXT, 448);
    expect(truncated).toMatchObject({ completeness: 'TRUNCATED', warnings: ['token budget reached'] });
    expect(freshness).toContain('partial');
    reads.enterResync('event gap');
    expect(reads.cacheCounts.decompiler).toBe(0);
    await expect(reads.decompile(CONTEXT, 448)).rejects.toBeInstanceOf(NativeReadResyncError);
    expect(reads.authoritativeReadsAllowed).toBe(false);

    transport.decompileResponse = result(decompile(448), CONTEXT, 'after-snapshot');
    reads.applySnapshot(CONTEXT);
    await expect(reads.decompile(CONTEXT, 448)).resolves.toMatchObject({ resultId: 'after-snapshot' });
    expect(reads.authoritativeReadsAllowed).toBe(true);
  });

  it('rejects malformed and over-budget transport fields before caching', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    const malformedSymbols = reads.symbols(CONTEXT, 'malformed', 10);
    await Promise.resolve();
    transport.symbolReads.get('malformed')?.resolve(result([{
      name: 'x'.repeat(1_025),
      kind: 'Function',
      row: 1,
      address: '0x00401004',
      confidence: 1,
    }]));
    await expect(malformedSymbols).rejects.toThrow(/symbol.name/);

    transport.decompileResponse = result({
      ...decompile(448),
      text: 'x'.repeat(2 * 1_024 * 1_024 + 1),
    });
    await expect(reads.decompile(CONTEXT, 448)).rejects.toThrow(/decompiler.text/);

    transport.evidenceResponse = result(Array.from({ length: 17 }, (_, index) => ({
      id: `evidence-${index}`,
      row: index,
      address: '0x00401000',
      title: 'bounded title',
      detail: 'd'.repeat(32_768),
      confidence: 0.5,
      classification: 'fact' as const,
    })));
    await expect(reads.evidence(CONTEXT, 0, 64)).rejects.toThrow(/Aggregate evidence/);
    expect(reads.cacheCounts).toMatchObject({ symbols: 0, decompiler: 0, evidence: 0 });
  });

  it('bounds symbol queries before cache-key construction or transport dispatch', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    await expect(reads.symbols(CONTEXT, 'q'.repeat(4_097), 10)).rejects.toThrow(/symbol query/);
    expect(transport.symbolReads.size).toBe(0);
    expect(reads.cacheCounts.symbols).toBe(0);
  });

  it('encodes delimiter-bearing runtime and program identities without collisions', () => {
    const left = { runtimeId: 'runtime:3', runtimeEpoch: 7, programId: 'program', contentGeneration: 11 };
    const right = { runtimeId: 'runtime', runtimeEpoch: 3, programId: '7:program', contentGeneration: 11 };
    const legacy = (context: ViewContext): string =>
      `${context.runtimeId}:${context.runtimeEpoch}:${context.programId}:${context.contentGeneration}`;
    expect(legacy(left)).toBe(legacy(right));
    expect(nativeReadContextKey(left)).not.toBe(nativeReadContextKey(right));
  });

  it('rejects regressive snapshots within one runtime and program incarnation', () => {
    const reads = new NativeReadService(new FakeTransport(), CONTEXT);
    expect(() => reads.applySnapshot({ ...CONTEXT, runtimeEpoch: CONTEXT.runtimeEpoch - 1 })).toThrow(NativeReadIdentityError);
    expect(() => reads.updateContext({ ...CONTEXT, contentGeneration: CONTEXT.contentGeneration - 1 })).toThrow(NativeReadIdentityError);
    expect(reads.context).toEqual(CONTEXT);
    expect(() => reads.applySnapshot({
      ...CONTEXT,
      runtimeId: 'new-runtime-incarnation',
      runtimeEpoch: 0,
      contentGeneration: 0,
    })).not.toThrow();
  });

  it('isolates activity listeners and caps untrusted error detail', async () => {
    const transport = new FakeTransport();
    transport.decompileFailure = new Error('x'.repeat(10_000));
    const reads = new NativeReadService(transport, CONTEXT);
    const details: string[] = [];
    reads.onDidChangeActivity(() => { throw new Error('broken status renderer'); });
    reads.onDidChangeActivity(activity => details.push(activity.detail));

    await expect(reads.decompile(CONTEXT, 448)).rejects.toThrow();
    expect(details).toHaveLength(2);
    expect(details.at(-1)).toHaveLength(2_048);
    expect(reads.cacheCounts.decompiler).toBe(0);
  });

  it('aborts the underlying transport on invalidation and disposal', async () => {
    const transport = new FakeTransport();
    const reads = new NativeReadService(transport, CONTEXT);
    const invalidated = reads.symbols(CONTEXT, 'invalidate-me', 10);
    await Promise.resolve();
    reads.invalidate();
    expect(transport.symbolSignals.get('invalidate-me')?.aborted).toBe(true);
    transport.symbolReads.get('invalidate-me')?.resolve(result([]));
    await expect(invalidated).rejects.toBeInstanceOf(NativeReadCancelledError);

    const disposed = reads.symbols(CONTEXT, 'dispose-me', 10);
    await Promise.resolve();
    reads.dispose();
    expect(transport.symbolSignals.get('dispose-me')?.aborted).toBe(true);
    transport.symbolReads.get('dispose-me')?.resolve(result([]));
    await expect(disposed).rejects.toBeInstanceOf(NativeReadCancelledError);
  });
});
