import { describe, expect, it } from 'vitest';
import { resolveBackendCapabilities } from './capabilities';
import { SyntheticEngine, formatAddress } from './engine';
import { NativeReadService, SyntheticReadTransport } from './readService';
import { GhidraExRepl } from './repl';

const SYNTHETIC_VIEW_CONTEXT = {
  runtimeId: 'runtime-local',
  runtimeEpoch: 3,
  programId: 'orbit-controller-v1',
  contentGeneration: 17,
} as const;

function context(currentRow = 448) {
  return {
    currentRow,
    capabilities: resolveBackendCapabilities({
      selectedMode: 'synthetic' as const,
      hostKind: 'desktop' as const,
      workspaceTrusted: true,
    }),
  };
}

function repl(maxHistory = 30): GhidraExRepl {
  const engine = new SyntheticEngine();
  return new GhidraExRepl(
    new NativeReadService(new SyntheticReadTransport(engine), SYNTHETIC_VIEW_CONTEXT),
    maxHistory,
  );
}

describe('GhidraEx bounded command REPL', () => {
  it('reports program and current-location state', async () => {
    const runtime = repl();
    expect((await runtime.execute('program', context())).output.join('\n')).toContain('100,000 instructions');
    expect((await runtime.execute('location', context())).output[0]).toContain(formatAddress(448));
  });

  it('resolves both symbols and aligned addresses for native navigation', async () => {
    const runtime = repl();
    expect(await runtime.execute('goto validate_crc', context())).toMatchObject({
      ok: true,
      action: { kind: 'navigate', row: 2_816 },
    });
    expect(await runtime.execute(`goto ${formatAddress(99_999)}`, context())).toMatchObject({
      ok: true,
      action: { kind: 'navigate', row: 99_999 },
    });
  });

  it('rejects arbitrary code and malformed locations without evaluating them', async () => {
    const runtime = repl();
    expect(await runtime.execute('eval globalThis', context())).toMatchObject({ ok: false });
    expect((await runtime.execute('goto 0x00401001', context())).output[0]).toContain('Unknown address or symbol');
  });

  it('prints decompiler, reference, and evidence data', async () => {
    const runtime = repl();
    expect((await runtime.execute('decompile decode_packet', context())).output.join('\n')).toContain('payloadLength');
    expect((await runtime.execute('xrefs decode_packet', context())).output).toHaveLength(3);
    expect((await runtime.execute('evidence decode_packet', context())).output).toEqual(expect.arrayContaining([
      expect.stringContaining('FACT'),
      expect.stringContaining('HYPOTHESIS'),
    ]));
  });

  it('exposes analysis and terminal lifecycle as explicit actions', async () => {
    const runtime = repl();
    expect((await runtime.execute('analyze', context())).action).toEqual({ kind: 'start-analysis' });
    expect((await runtime.execute('clear', context())).action).toEqual({ kind: 'clear' });
    expect((await runtime.execute('exit', context())).action).toEqual({ kind: 'close' });
  });

  it('renders backend and plugin limitations in-band', async () => {
    const runtime = repl();
    expect((await runtime.execute('backend', context())).output).toContain('real Ghidra ready: no');
    expect((await runtime.execute('plugins', context())).output.join('\n')).toContain('Ghidra JVM bridge');
    expect((await runtime.execute('capabilities', context())).output.join('\n')).toContain('VS Code debug session');
  });

  it('runs a comment-aware gx command script and stops at the first failure', async () => {
    const runtime = repl();
    const batch = await runtime.executeCommandScript([
      '# inspect the active fixture',
      'program',
      'decompile decode_packet',
      'unknown-command',
      'xrefs decode_packet',
    ].join('\n'), context());
    expect(batch.ok).toBe(false);
    expect(batch.results.map(result => result.input)).toEqual(['program', 'decompile decode_packet', 'unknown-command']);
  });

  it('carries navigation forward as the current script location', async () => {
    const runtime = repl();
    const batch = await runtime.executeCommandScript('goto validate_crc\nlocation', context());
    expect(batch.ok).toBe(true);
    expect(batch.results[1]?.output[0]).toContain(formatAddress(2_816));
  });

  it('bounds native history and supports an explicit clear', async () => {
    const runtime = repl(2);
    await runtime.execute('program', context());
    await runtime.execute('location', context());
    await runtime.execute('help', context());
    expect(runtime.history.map(result => result.input)).toEqual(['location', 'help']);
    runtime.clearHistory();
    expect(runtime.history).toEqual([]);
  });
});
