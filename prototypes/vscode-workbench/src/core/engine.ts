export const PROGRAM_ID = 'orbit-controller-v1';
export const PROGRAM_REVISION = 17;
export const TOTAL_ROWS = 100_000;
export const PAGE_SIZE = 256;
export const MAX_CACHED_PAGES = 8;
export const BASE_ADDRESS = 0x0040_1000;

export interface ProgramSummary {
  readonly id: string;
  readonly name: string;
  readonly format: string;
  readonly language: string;
  readonly revision: number;
  readonly imageBase: string;
  readonly instructionCount: number;
  readonly status: 'partially-analyzed' | 'ready';
}

export interface Instruction {
  readonly index: number;
  readonly address: string;
  readonly bytes: string;
  readonly mnemonic: string;
  readonly operands: string;
  readonly annotation?: string;
}

export interface ListingPage {
  readonly start: number;
  readonly count: number;
  readonly total: number;
  readonly rows: readonly Instruction[];
}

export interface SymbolMatch {
  readonly name: string;
  readonly kind: 'Function' | 'Label' | 'Import';
  readonly row: number;
  readonly address: string;
  readonly confidence: number;
}

export interface DecompileResult {
  readonly functionName: string;
  readonly signature: string;
  readonly text: string;
  readonly row: number;
  readonly address: string;
}

export interface AnalysisProgress {
  readonly percent: number;
  readonly phase: string;
  readonly detail: string;
}

export interface ReferenceMatch {
  readonly sourceRow: number;
  readonly sourceAddress: string;
  readonly kind: 'CALL' | 'READ' | 'WRITE';
  readonly functionName: string;
}

export interface EvidenceRecord {
  readonly id: string;
  readonly row: number;
  readonly address: string;
  readonly title: string;
  readonly detail: string;
  readonly confidence: number;
  readonly classification: 'fact' | 'hypothesis';
}

export class CancelledError extends Error {
  public constructor() {
    super('Analysis cancelled');
    this.name = 'CancelledError';
  }
}

const MNEMONICS = [
  ['push', 'rbp'],
  ['mov', 'rbp, rsp'],
  ['sub', 'rsp, 0x20'],
  ['mov', 'eax, dword ptr [rdi+0x8]'],
  ['cmp', 'eax, 0x1000'],
  ['ja', '0x00401090'],
  ['lea', 'rsi, [rdi+0x10]'],
  ['call', 'decode_packet'],
  ['test', 'eax, eax'],
  ['jne', '0x00401130'],
  ['xor', 'ecx, ecx'],
  ['movzx', 'edx, byte ptr [rsi+rcx]'],
  ['rol', 'edx, 0x5'],
  ['add', 'eax, edx'],
  ['inc', 'rcx'],
  ['jmp', '0x0040102c'],
] as const;

const SYMBOLS = [
  ['entry', 'Function', 0, 1.0],
  ['decode_packet', 'Function', 448, 0.98],
  ['validate_crc', 'Function', 2_816, 0.96],
  ['dispatch_command', 'Function', 8_192, 0.94],
  ['parse_header', 'Function', 16_384, 0.91],
  ['decrypt_payload', 'Function', 32_768, 0.88],
  ['socket_read', 'Import', 49_152, 1.0],
  ['telemetry_ring', 'Label', 65_536, 0.86],
  ['panic_handler', 'Function', 81_920, 0.99],
  ['finalizer', 'Function', 99_936, 0.93],
] as const satisfies readonly (readonly [string, SymbolMatch['kind'], number, number])[];

export function formatAddress(index: number): string {
  return `0x${(BASE_ADDRESS + index * 4).toString(16).padStart(8, '0')}`;
}

export function rowForAddress(value: string): number | undefined {
  const normalized = value.trim().toLowerCase().replaceAll('_', '');
  const numeric = Number.parseInt(normalized.startsWith('0x') ? normalized.slice(2) : normalized, 16);
  if (!Number.isFinite(numeric) || numeric < BASE_ADDRESS) {
    return undefined;
  }
  const delta = numeric - BASE_ADDRESS;
  if (delta % 4 !== 0) {
    return undefined;
  }
  const row = delta / 4;
  return row >= 0 && row < TOTAL_ROWS ? row : undefined;
}

function instructionAt(index: number): Instruction {
  const pair = MNEMONICS[index % MNEMONICS.length] ?? MNEMONICS[0];
  const byte = ((index * 37 + 0x55) & 0xff).toString(16).padStart(2, '0');
  const annotation = index % 448 === 0 ? `LAB_${formatAddress(index).slice(2)}` : undefined;
  return {
    index,
    address: formatAddress(index),
    bytes: `${byte} ${(Number.parseInt(byte, 16) ^ 0xa5).toString(16).padStart(2, '0')} 48 89`,
    mnemonic: pair[0],
    operands: pair[1],
    ...(annotation === undefined ? {} : { annotation }),
  };
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new CancelledError());
      return;
    }
    const timer = setTimeout(resolve, milliseconds);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      reject(new CancelledError());
    }, { once: true });
  });
}

export class SyntheticEngine {
  private readonly pageCache = new Map<number, readonly Instruction[]>();

  public openProgram(): ProgramSummary {
    return {
      id: PROGRAM_ID,
      name: 'orbit-controller-v1',
      format: 'ELF x86-64',
      language: 'x86:LE:64:default',
      revision: PROGRAM_REVISION,
      imageBase: formatAddress(0),
      instructionCount: TOTAL_ROWS,
      status: 'partially-analyzed',
    };
  }

  public listing(start: number, count = PAGE_SIZE): ListingPage {
    if (!Number.isInteger(start) || start < 0 || start >= TOTAL_ROWS) {
      throw new RangeError(`Listing start ${start} is outside the program`);
    }
    if (!Number.isInteger(count) || count < 1 || count > PAGE_SIZE) {
      throw new RangeError(`Listing count must be between 1 and ${PAGE_SIZE}`);
    }

    const pageStart = Math.floor(start / PAGE_SIZE) * PAGE_SIZE;
    let page = this.pageCache.get(pageStart);
    if (page === undefined) {
      const pageEnd = Math.min(pageStart + PAGE_SIZE, TOTAL_ROWS);
      page = Array.from({ length: pageEnd - pageStart }, (_, offset) => instructionAt(pageStart + offset));
      this.pageCache.set(pageStart, page);
      if (this.pageCache.size > MAX_CACHED_PAGES) {
        const oldest = this.pageCache.keys().next().value as number | undefined;
        if (oldest !== undefined) {
          this.pageCache.delete(oldest);
        }
      }
    } else {
      this.pageCache.delete(pageStart);
      this.pageCache.set(pageStart, page);
    }

    const offset = start - pageStart;
    const rows = page.slice(offset, Math.min(offset + count, page.length));
    return { start, count: rows.length, total: TOTAL_ROWS, rows };
  }

  public get cacheEntryCount(): number {
    return this.pageCache.size;
  }

  public searchSymbols(query: string, limit = 12): readonly SymbolMatch[] {
    const needle = query.trim().toLowerCase();
    return SYMBOLS
      .filter(([name]) => needle.length === 0 || name.includes(needle))
      .slice(0, Math.max(1, Math.min(limit, 50)))
      .map(([name, kind, row, confidence]) => ({
        name,
        kind,
        row,
        address: formatAddress(row),
        confidence,
      }));
  }

  public decompile(row: number): DecompileResult {
    const functionRow = Math.floor(Math.max(0, Math.min(row, TOTAL_ROWS - 1)) / 64) * 64;
    const known = SYMBOLS.find(([, kind, symbolRow]) => kind === 'Function' && symbolRow === functionRow);
    const functionName = known?.[0] ?? `FUN_${formatAddress(functionRow).slice(2)}`;
    const address = formatAddress(functionRow);
    return {
      functionName,
      signature: `int ${functionName}(Packet *packet, Context *ctx)`,
      row: functionRow,
      address,
      text: `int ${functionName}(Packet *packet, Context *ctx) {\n` +
        `    uint32_t payloadLength = packet->payloadLength;\n` +
        `    if (payloadLength > 0x1000) {\n` +
        `        audit_reject(ctx, ${address});\n` +
        `        return -1;\n` +
        `    }\n` +
        `    int decoded = decode_payload(packet->payload, payloadLength);\n` +
        `    return decoded ^ ctx->sessionKey;\n` +
        `}`,
    };
  }

  public referencesTo(row: number): readonly ReferenceMatch[] {
    const offsets = [-173, 701, 2_337] as const;
    const kinds = ['CALL', 'READ', 'WRITE'] as const;
    return offsets.map((offset, index) => {
      const sourceRow = Math.max(0, Math.min(TOTAL_ROWS - 1, row + offset));
      return {
        sourceRow,
        sourceAddress: formatAddress(sourceRow),
        kind: kinds[index] ?? 'CALL',
        functionName: this.decompile(sourceRow).functionName,
      };
    });
  }

  public evidenceFor(row: number): readonly EvidenceRecord[] {
    const current = Math.max(0, Math.min(TOTAL_ROWS - 1, row));
    return [
      {
        id: 'length-dominates-decode',
        row: current,
        address: formatAddress(current),
        title: 'Length guard dominates decode',
        detail: 'The selected comparison bounds payloadLength before the decode call.',
        confidence: 0.92,
        classification: 'fact',
      },
      {
        id: 'signedness-hypothesis',
        row: Math.min(TOTAL_ROWS - 1, current + 4),
        address: formatAddress(Math.min(TOTAL_ROWS - 1, current + 4)),
        title: 'Possible signedness mismatch',
        detail: 'Hypothesis: the caller widens a byte before the bounds comparison.',
        confidence: 0.68,
        classification: 'hypothesis',
      },
    ];
  }

  public async runAnalysis(
    signal: AbortSignal,
    onProgress: (progress: AnalysisProgress) => void,
  ): Promise<void> {
    const phases = [
      [8, 'Discovering code', 'Scanning executable blocks'],
      [31, 'Recovering functions', 'Propagating call targets'],
      [57, 'Resolving references', 'Indexing cross-references'],
      [79, 'Inferring types', 'Applying data-flow constraints'],
      [100, 'Complete', 'Analysis model is current'],
    ] as const;
    for (const [percent, phase, detail] of phases) {
      await abortableDelay(420, signal);
      if (signal.aborted) {
        throw new CancelledError();
      }
      onProgress({ percent, phase, detail });
    }
  }
}
