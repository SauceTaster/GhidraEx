import { describe, expect, it } from 'vitest';
import {
  CancelledError,
  MAX_CACHED_PAGES,
  PAGE_SIZE,
  SyntheticEngine,
  TOTAL_ROWS,
  formatAddress,
  rowForAddress,
} from './engine';

describe('SyntheticEngine', () => {
  it('reports the shared 100,000-instruction fixture', () => {
    const engine = new SyntheticEngine();
    expect(engine.openProgram().instructionCount).toBe(100_000);
    expect(engine.openProgram().status).toBe('partially-analyzed');
  });

  it('retrieves deterministic first, middle, and final instructions without a whole-program array', () => {
    const engine = new SyntheticEngine();
    for (const row of [0, 50_000, 99_999]) {
      const result = engine.listing(row, 1);
      expect(result.rows).toHaveLength(1);
      expect(result.rows[0]?.index).toBe(row);
      expect(result.rows[0]?.address).toBe(formatAddress(row));
      expect(result.total).toBe(TOTAL_ROWS);
    }
    expect(engine.cacheEntryCount).toBe(3);
  });

  it('rejects unbounded listing requests', () => {
    const engine = new SyntheticEngine();
    expect(() => engine.listing(0, PAGE_SIZE + 1)).toThrow(RangeError);
    expect(() => engine.listing(TOTAL_ROWS, 1)).toThrow(RangeError);
  });

  it('keeps the page cache bounded with LRU eviction', () => {
    const engine = new SyntheticEngine();
    for (let page = 0; page < MAX_CACHED_PAGES + 5; page += 1) {
      engine.listing(page * PAGE_SIZE, 1);
    }
    expect(engine.cacheEntryCount).toBe(MAX_CACHED_PAGES);
  });

  it('round-trips mapped addresses and rejects misaligned values', () => {
    expect(rowForAddress(formatAddress(99_999))).toBe(99_999);
    expect(rowForAddress('0x00401001')).toBeUndefined();
    expect(rowForAddress('not-an-address')).toBeUndefined();
  });

  it('searches symbols and derives a decompiler document', () => {
    const engine = new SyntheticEngine();
    expect(engine.searchSymbols('packet').map(result => result.name)).toContain('decode_packet');
    const result = engine.decompile(448);
    expect(result.functionName).toBe('decode_packet');
    expect(result.text).toContain('payloadLength');
  });

  it('returns navigable xrefs and provenance-bearing evidence for native views', () => {
    const engine = new SyntheticEngine();
    expect(engine.referencesTo(448)).toHaveLength(3);
    expect(engine.referencesTo(448).every(reference => reference.sourceAddress.startsWith('0x'))).toBe(true);
    expect(engine.evidenceFor(452)).toEqual(expect.arrayContaining([
      expect.objectContaining({ classification: 'fact', confidence: 0.92 }),
      expect.objectContaining({ classification: 'hypothesis', confidence: 0.68 }),
    ]));
  });

  it('acknowledges cancellation without emitting completion', async () => {
    const engine = new SyntheticEngine();
    const controller = new AbortController();
    const progress: number[] = [];
    const running = engine.runAnalysis(controller.signal, update => progress.push(update.percent));
    controller.abort();
    await expect(running).rejects.toBeInstanceOf(CancelledError);
    expect(progress).not.toContain(100);
  });
});
