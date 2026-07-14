import type { BackendCapabilitySnapshot } from './capabilities';
import { formatAddress, rowForAddress } from './engine';
import type { ContextualReadResult, NativeReadService } from './readService';

export type ReplAction =
  | { readonly kind: 'navigate'; readonly row: number }
  | { readonly kind: 'start-analysis' }
  | { readonly kind: 'clear' }
  | { readonly kind: 'close' };

export interface ReplResult {
  readonly input: string;
  readonly ok: boolean;
  readonly output: readonly string[];
  readonly action?: ReplAction;
}

export interface ScriptBatchResult {
  readonly ok: boolean;
  readonly results: readonly ReplResult[];
}

export interface ReplExecutionContext {
  readonly currentRow: number;
  readonly capabilities: BackendCapabilitySnapshot;
}

const HELP = [
  'help                         show this command list',
  'program                      summarize the active program',
  'location                     show the selected address and function',
  'goto <address|symbol>        navigate native editors',
  'decompile [address|symbol]   print deterministic decompiler text',
  'xrefs [address|symbol]       list references',
  'evidence [address|symbol]    list findings and hypotheses',
  'analyze                      start the native analyzer workflow',
  'backend                      show requested/effective backend state',
  'capabilities                 show backend capability states',
  'plugins                      show plugin/bridge states',
  'clear                        clear the terminal',
  'exit                         close the terminal',
] as const;

function firstArgument(input: string): string | undefined {
  const match = /^\S+\s+(.+)$/.exec(input.trim());
  const argument = match?.[1]?.trim();
  return argument && argument.length > 0 ? argument : undefined;
}

export class GhidraExRepl {
  private records: ReplResult[] = [];

  public constructor(
    private readonly reads: NativeReadService,
    private readonly maxHistory = 30,
  ) {}

  public get history(): readonly ReplResult[] {
    return this.records;
  }

  public clearHistory(): void {
    this.records = [];
  }

  public async execute(
    rawInput: string,
    context: ReplExecutionContext,
    signal?: AbortSignal,
  ): Promise<ReplResult> {
    const input = rawInput.trim();
    if (input.length === 0) return { input, ok: true, output: [] };
    const readSignal = signal ?? new AbortController().signal;

    const command = input.split(/\s+/, 1)[0]?.toLowerCase() ?? '';
    const argument = firstArgument(input);
    let result: ReplResult;

    switch (command) {
      case 'help':
      case '?':
        result = { input, ok: true, output: HELP };
        break;
      case 'program': {
        const read = await this.reads.program({ signal: readSignal, scope: 'repl' });
        const summary = read.value;
        result = {
          input,
          ok: true,
          output: [
            `${summary.name} · ${summary.format}`,
            `${summary.language} · ${summary.instructionCount.toLocaleString()} instructions`,
            `image base ${summary.imageBase} · revision ${summary.revision} · ${summary.status}`,
            ...this.readWarnings(read),
          ],
        };
        break;
      }
      case 'location': {
        const row = await this.resolveRow(argument, context.currentRow, readSignal);
        const decompiled = row === undefined
          ? undefined
          : await this.reads.decompile(this.reads.context, row, { signal: readSignal, scope: 'repl' });
        result = row === undefined
          ? this.invalidLocation(input, argument)
          : {
            input,
            ok: true,
            output: [
              `${formatAddress(row)} · ${decompiled?.value.functionName ?? 'unknown'} · row ${row.toLocaleString()}`,
              ...(decompiled === undefined ? [] : this.readWarnings(decompiled)),
            ],
          };
        break;
      }
      case 'goto': {
        if (argument === undefined) {
          result = { input, ok: false, output: ['Usage: goto <address|symbol>'] };
          break;
        }
        const row = await this.resolveRow(argument, context.currentRow, readSignal);
        const decompiled = row === undefined
          ? undefined
          : await this.reads.decompile(this.reads.context, row, { signal: readSignal, scope: 'repl' });
        result = row === undefined
          ? this.invalidLocation(input, argument)
          : {
            input,
            ok: true,
            output: [
              `Navigating to ${formatAddress(row)} (${decompiled?.value.functionName ?? 'unknown'})`,
              ...(decompiled === undefined ? [] : this.readWarnings(decompiled)),
            ],
            action: { kind: 'navigate', row },
          };
        break;
      }
      case 'decompile': {
        const row = await this.resolveRow(argument, context.currentRow, readSignal);
        if (row === undefined) {
          result = this.invalidLocation(input, argument);
          break;
        }
        const read = await this.reads.decompile(this.reads.context, row, { signal: readSignal, scope: 'repl' });
        const decompiled = read.value;
        result = {
          input,
          ok: true,
          output: [`${decompiled.signature} @ ${decompiled.address}`, decompiled.text, ...this.readWarnings(read)],
        };
        break;
      }
      case 'xrefs': {
        const row = await this.resolveRow(argument, context.currentRow, readSignal);
        const read = row === undefined
          ? undefined
          : await this.reads.references(this.reads.context, row, 256, { signal: readSignal, scope: 'repl' });
        result = row === undefined
          ? this.invalidLocation(input, argument)
          : {
            input,
            ok: true,
            output: [
              ...(read?.value ?? []).map(reference =>
                `${reference.kind.padEnd(5)} ${reference.sourceAddress} ${reference.functionName}`,
              ),
              ...(read === undefined ? [] : this.readWarnings(read)),
            ],
          };
        break;
      }
      case 'evidence': {
        const row = await this.resolveRow(argument, context.currentRow, readSignal);
        const read = row === undefined
          ? undefined
          : await this.reads.evidence(this.reads.context, row, 64, { signal: readSignal, scope: 'repl' });
        result = row === undefined
          ? this.invalidLocation(input, argument)
          : {
            input,
            ok: true,
            output: [
              ...(read?.value ?? []).map(evidence =>
                `${evidence.classification.toUpperCase()} ${Math.round(evidence.confidence * 100)}% ${evidence.address} ${evidence.title}`,
              ),
              ...(read === undefined ? [] : this.readWarnings(read)),
            ],
          };
        break;
      }
      case 'analyze':
        result = {
          input,
          ok: true,
          output: ['Starting the native analyzer pipeline. Progress appears in the Analysis view.'],
          action: { kind: 'start-analysis' },
        };
        break;
      case 'backend':
        result = {
          input,
          ok: true,
          output: [
            `requested: ${context.capabilities.selectedMode}`,
            `effective: ${context.capabilities.effectiveMode}${context.capabilities.fallbackActive ? ' (fallback)' : ''}`,
            `real Ghidra ready: ${context.capabilities.realBackendReady ? 'yes' : 'no'}`,
            `${context.capabilities.backend.state}: ${context.capabilities.backend.detail}`,
          ],
        };
        break;
      case 'capabilities':
        result = {
          input,
          ok: true,
          output: context.capabilities.capabilities.map(capability =>
            `${capability.state.padEnd(12)} ${capability.title} — ${capability.detail}`,
          ),
        };
        break;
      case 'plugins':
        result = {
          input,
          ok: true,
          output: context.capabilities.plugins.map(plugin =>
            `${plugin.state.padEnd(12)} ${plugin.title} [${plugin.kind}] — ${plugin.detail}`,
          ),
        };
        break;
      case 'clear':
        result = { input, ok: true, output: [], action: { kind: 'clear' } };
        break;
      case 'exit':
      case 'quit':
        result = { input, ok: true, output: ['Closing GhidraEx Script Console.'], action: { kind: 'close' } };
        break;
      default:
        result = { input, ok: false, output: [`Unknown command “${command}”. Type help for the bounded gx command set.`] };
    }

    this.record(result);
    return result;
  }

  public async executeCommandScript(
    source: string,
    context: ReplExecutionContext,
    signal?: AbortSignal,
  ): Promise<ScriptBatchResult> {
    const commands = source
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(line => line.length > 0 && !line.startsWith('#'));
    const results: ReplResult[] = [];
    let currentRow = context.currentRow;
    for (const command of commands) {
      const result = await this.execute(command, { ...context, currentRow }, signal);
      results.push(result);
      if (result.action?.kind === 'navigate') currentRow = result.action.row;
      if (!result.ok || result.action?.kind === 'close') break;
    }
    return { ok: results.every(result => result.ok), results };
  }

  private async resolveRow(
    argument: string | undefined,
    currentRow: number,
    signal?: AbortSignal,
  ): Promise<number | undefined> {
    if (argument === undefined) return currentRow;
    const addressRow = rowForAddress(argument);
    if (addressRow !== undefined) return addressRow;
    const normalized = argument.toLowerCase();
    const exact = (await this.reads.symbols(this.reads.context, argument, 50, {
      signal,
      scope: 'repl-symbol-resolution',
    })).value.find(symbol => symbol.name.toLowerCase() === normalized);
    return exact?.row;
  }

  private readWarnings<T>(result: ContextualReadResult<T>): readonly string[] {
    if (result.completeness === 'COMPLETE') return [];
    return [
      `[${result.completeness}] ${result.warnings.join(' · ') || 'Backend returned incomplete coverage.'}`,
    ];
  }

  private invalidLocation(input: string, value: string | undefined): ReplResult {
    return {
      input,
      ok: false,
      output: [`Unknown address or symbol${value === undefined ? '.' : ` “${value}”.`}`],
    };
  }

  private record(result: ReplResult): void {
    this.records.push(result);
    if (this.records.length > this.maxHistory) {
      this.records.splice(0, this.records.length - this.maxHistory);
    }
  }
}
