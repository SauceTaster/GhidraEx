import * as vscode from 'vscode';
import {
  BASE_ADDRESS,
  PAGE_SIZE,
  PROGRAM_ID,
  PROGRAM_REVISION,
  TOTAL_ROWS,
  formatAddress,
  rowForAddress,
  type DecompileResult,
  type Instruction,
  type SymbolMatch,
} from '../core/engine';
import {
  NativeReadService,
  sameReadContext,
  type NativeReadCompleteness,
} from '../core/readService';
import {
  MAX_LISTING_WINDOW_ROWS,
  type AddressRef,
  type ListingRow,
  type LocationRef,
  type ViewContext,
} from '../core/viewState';
import { NativeReadCancellationPool } from './asyncReads';

export const LISTING_SCHEME = 'ghidraex-listing';
export const DECOMPILER_SCHEME = 'ghidraex-decompiler';

export const NATIVE_COMMANDS = {
  openListingAt: 'ghidraex.openNativeListingAt',
  openDecompiler: 'ghidraex.openNativeDecompiler',
} as const;

const ADDRESS_START = 0;
const ADDRESS_LENGTH = 10;
const BYTES_START = 12;
const BYTES_WIDTH = 11;
const MNEMONIC_START = 25;
const MNEMONIC_WIDTH = 8;
const OPERANDS_START = MNEMONIC_START + MNEMONIC_WIDTH;
const REFERENCE_LIMIT = 256;
const MAX_CACHED_LISTING_PROJECTIONS = 8;
const MAX_CACHED_DECOMPILATIONS = 64;

export const SYNTHETIC_RUNTIME_ID = 'runtime-local';
export const SYNTHETIC_RUNTIME_EPOCH = 3;
export const SYNTHETIC_SPACE_ID = 'ram';
export const SYNTHETIC_SPACE_EPOCH = 1;
export const LISTING_WINDOW_ROWS = MAX_LISTING_WINDOW_ROWS;

export const SYNTHETIC_VIEW_CONTEXT: ViewContext = {
  runtimeId: SYNTHETIC_RUNTIME_ID,
  runtimeEpoch: SYNTHETIC_RUNTIME_EPOCH,
  programId: PROGRAM_ID,
  contentGeneration: PROGRAM_REVISION,
};

export type NativeDocumentDescriptor =
  | {
      readonly kind: 'listing';
      readonly context: ViewContext;
      readonly spaceId: string;
      readonly spaceEpoch: number;
      readonly startRow: number;
      readonly rowCount: number;
    }
  | {
      readonly kind: 'decompiler';
      readonly context: ViewContext;
      readonly row: number;
    };

function requireGlobalRow(row: number): void {
  if (!Number.isInteger(row) || row < 0 || row >= TOTAL_ROWS) {
    throw new RangeError(`Global row ${row} is outside the program`);
  }
}

export function listingWindowStartForRow(row: number): number {
  requireGlobalRow(row);
  return Math.floor(row / LISTING_WINDOW_ROWS) * LISTING_WINDOW_ROWS;
}

export function listingWindowRowCount(startRow: number): number {
  requireGlobalRow(startRow);
  return Math.min(LISTING_WINDOW_ROWS, TOTAL_ROWS - startRow);
}

function contextQuery(context: ViewContext): URLSearchParams {
  return new URLSearchParams({
    runtimeEpoch: String(context.runtimeEpoch),
    programId: context.programId,
    contentGeneration: String(context.contentGeneration),
  });
}

function queryInteger(params: URLSearchParams, name: string): number | undefined {
  const raw = params.get(name);
  if (raw === null || raw.length === 0 || !/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : undefined;
}

function parseContext(uri: vscode.Uri, params: URLSearchParams): ViewContext | undefined {
  const runtimeEpoch = queryInteger(params, 'runtimeEpoch');
  const contentGeneration = queryInteger(params, 'contentGeneration');
  const programId = params.get('programId');
  if (uri.authority.length === 0 || runtimeEpoch === undefined || contentGeneration === undefined ||
      programId === null || programId.length === 0) {
    return undefined;
  }
  return { runtimeId: uri.authority, runtimeEpoch, programId, contentGeneration };
}

/** Returns the canonical URI for the bounded listing projection containing `targetRow`. */
export function makeListingUri(
  targetRow = 0,
  context: ViewContext = SYNTHETIC_VIEW_CONTEXT,
): vscode.Uri {
  requireGlobalRow(targetRow);
  const startRow = listingWindowStartForRow(targetRow);
  const rowCount = listingWindowRowCount(startRow);
  const query = contextQuery(context);
  query.set('spaceId', SYNTHETIC_SPACE_ID);
  query.set('spaceEpoch', String(SYNTHETIC_SPACE_EPOCH));
  query.set('startRow', String(startRow));
  query.set('rowCount', String(rowCount));
  return vscode.Uri.from({
    scheme: LISTING_SCHEME,
    authority: context.runtimeId,
    path: `/${context.programId}/${SYNTHETIC_SPACE_ID}/${startRow}.listing.asm`,
    query: query.toString(),
  });
}

/** Returns one canonical decompiler document URI per containing function. */
export function makeDecompilerUri(
  row: number,
  context: ViewContext = SYNTHETIC_VIEW_CONTEXT,
): vscode.Uri {
  requireGlobalRow(row);
  const functionRow = Math.floor(row / 64) * 64;
  const query = contextQuery(context);
  query.set('row', String(functionRow));
  return vscode.Uri.from({
    scheme: DECOMPILER_SCHEME,
    authority: context.runtimeId,
    path: `/${context.programId}/FUN_${formatAddress(functionRow).slice(2)}.c`,
    query: query.toString(),
  });
}

/** Parses a GhidraEx virtual-document URI without trusting its contents. */
export function parseNativeDocumentUri(uri: vscode.Uri): NativeDocumentDescriptor | undefined {
  if (uri.scheme !== LISTING_SCHEME && uri.scheme !== DECOMPILER_SCHEME) {
    return undefined;
  }
  const params = new URLSearchParams(uri.query);
  const context = parseContext(uri, params);
  if (context === undefined) return undefined;
  if (uri.scheme === LISTING_SCHEME) {
    const spaceId = params.get('spaceId');
    const spaceEpoch = queryInteger(params, 'spaceEpoch');
    const startRow = queryInteger(params, 'startRow');
    const rowCount = queryInteger(params, 'rowCount');
    if (spaceId === null || spaceId.length === 0 || spaceEpoch === undefined || startRow === undefined ||
        rowCount === undefined || startRow >= TOTAL_ROWS || rowCount < 1 ||
        rowCount > MAX_LISTING_WINDOW_ROWS || startRow + rowCount > TOTAL_ROWS) {
      return undefined;
    }
    return { kind: 'listing', context, spaceId, spaceEpoch, startRow, rowCount };
  }
  const row = queryInteger(params, 'row');
  return row === undefined || row >= TOTAL_ROWS ? undefined : { kind: 'decompiler', context, row };
}

function requireDescriptor<K extends NativeDocumentDescriptor['kind']>(
  reads: NativeReadService,
  uri: vscode.Uri,
  expectedKind: K,
): Extract<NativeDocumentDescriptor, { readonly kind: K }> {
  const descriptor = parseNativeDocumentUri(uri);
  if (descriptor === undefined || descriptor.kind !== expectedKind ||
      !sameReadContext(descriptor.context, reads.context)) {
    throw vscode.FileSystemError.FileNotFound(uri);
  }
  return descriptor as Extract<NativeDocumentDescriptor, { readonly kind: K }>;
}

export function listingEditorLineForGlobalRow(uri: vscode.Uri, globalRow: number): number | undefined {
  const descriptor = parseNativeDocumentUri(uri);
  if (descriptor?.kind !== 'listing' || !Number.isInteger(globalRow) ||
      globalRow < descriptor.startRow || globalRow >= descriptor.startRow + descriptor.rowCount) {
    return undefined;
  }
  return globalRow - descriptor.startRow;
}

export function listingGlobalRowForEditorLine(uri: vscode.Uri, editorLine: number): number | undefined {
  const descriptor = parseNativeDocumentUri(uri);
  if (descriptor?.kind !== 'listing' || !Number.isInteger(editorLine) ||
      editorLine < 0 || editorLine >= descriptor.rowCount) {
    return undefined;
  }
  return descriptor.startRow + editorLine;
}

export function addressRefForGlobalRow(globalRow: number, spaceId = SYNTHETIC_SPACE_ID): AddressRef {
  requireGlobalRow(globalRow);
  const offset = BigInt(BASE_ADDRESS) + BigInt(globalRow) * 4n;
  return {
    spaceId,
    spaceEpoch: SYNTHETIC_SPACE_EPOCH,
    offsetBits: offset.toString(16),
    display: `${spaceId}:${offset.toString(16).padStart(8, '0')}`,
  };
}

export function locationRefForGlobalRow(globalRow: number, fieldId = 'mnemonic'): LocationRef {
  const address = addressRefForGlobalRow(globalRow);
  return { requestedAddress: address, containingAddress: address, byteOffset: 0, fieldId };
}

function symbolKind(symbol: SymbolMatch): vscode.SymbolKind {
  switch (symbol.kind) {
    case 'Function': return vscode.SymbolKind.Function;
    case 'Import': return vscode.SymbolKind.Interface;
    case 'Label': return vscode.SymbolKind.Variable;
  }
}

function semanticTypeForSymbol(symbol: SymbolMatch): 'function' | 'variable' | 'namespace' {
  switch (symbol.kind) {
    case 'Function': return 'function';
    case 'Label': return 'variable';
    case 'Import': return 'namespace';
  }
}

async function allSymbols(
  reads: NativeReadService,
  context: ViewContext,
  signal: AbortSignal,
  scope: string,
): Promise<readonly SymbolMatch[]> {
  return (await reads.symbols(context, '', 50, { signal, scope })).value;
}

async function symbolsByRow(
  reads: NativeReadService,
  context: ViewContext,
  signal: AbortSignal,
  scope: string,
): Promise<ReadonlyMap<number, SymbolMatch>> {
  return new Map((await allSymbols(reads, context, signal, scope)).map(symbol => [symbol.row, symbol]));
}

export function renderListingLine(instruction: Instruction, symbol?: SymbolMatch): string {
  const notes: string[] = [];
  if (instruction.annotation !== undefined) notes.push(instruction.annotation);
  if (symbol !== undefined) notes.push(`${symbol.kind.toLowerCase()} ${symbol.name}`);
  const code = `${instruction.address}  ${instruction.bytes.padEnd(BYTES_WIDTH)}  ` +
    `${instruction.mnemonic.padEnd(MNEMONIC_WIDTH)}${instruction.operands}`;
  return notes.length === 0 ? code : `${code}  ; ${notes.join(' · ')}`;
}

export interface ListingDocumentLineRef {
  readonly editorLine: number;
  readonly globalRow: number;
  readonly semanticRow: ListingRow;
}

export interface ListingDocumentProjection {
  readonly uri: vscode.Uri;
  readonly context: ViewContext;
  readonly startRow: number;
  readonly rowCount: number;
  readonly content: string;
  readonly lines: readonly ListingDocumentLineRef[];
  readonly completeness: NativeReadCompleteness;
  readonly warnings: readonly string[];
}

function combineCompleteness(
  left: NativeReadCompleteness,
  right: NativeReadCompleteness,
): NativeReadCompleteness {
  if (left === 'TRUNCATED' || right === 'TRUNCATED') return 'TRUNCATED';
  if (left === 'PARTIAL' || right === 'PARTIAL') return 'PARTIAL';
  return 'COMPLETE';
}

export interface ListingLineResolver {
  lineRef(uri: vscode.Uri, editorLine: number): ListingDocumentLineRef | undefined;
  editorLineForGlobalRow(uri: vscode.Uri, globalRow: number): number | undefined;
}

function semanticRowForInstruction(instruction: Instruction): ListingRow {
  const location = locationRefForGlobalRow(instruction.index);
  return {
    rowId: `${location.containingAddress.spaceId}:${location.containingAddress.offsetBits}:unit`,
    kind: 'INSTRUCTION',
    location,
    fields: {
      address: instruction.address,
      bytes: instruction.bytes,
      mnemonic: instruction.mnemonic,
      operands: instruction.operands,
      ...(instruction.annotation === undefined ? {} : { annotation: instruction.annotation }),
    },
  };
}

export class ListingDocumentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<vscode.Uri>();
  private readonly cancellations = new NativeReadCancellationPool();
  private readonly projections = new Map<string, ListingDocumentProjection>();
  private readonly knownUris = new Map<string, vscode.Uri>();
  private readonly invalidated = new Set<string>();
  private readonly latestByUri = new Map<string, number>();
  private requestSequence = 0;
  private disposed = false;
  public readonly onDidChange = this.changed.event;

  public constructor(private readonly reads: NativeReadService) {}

  public async provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): Promise<string> {
    return this.cancellations.run(token, async signal => {
      const descriptor = requireDescriptor(this.reads, uri, 'listing');
      const key = uri.toString();
      const requestId = ++this.requestSequence;
      this.latestByUri.set(key, requestId);
      const cached = this.projections.get(key);
      if (cached !== undefined && !this.invalidated.has(key)) {
        this.touchProjection(key, cached);
        return cached.content;
      }

      const scope = `listing-document:${key}:${requestId}`;
      const symbolResult = await this.reads.symbols(descriptor.context, '', 50, {
        signal,
        scope: `${scope}:symbols`,
      });
      const symbolMap = new Map(symbolResult.value.map(symbol => [symbol.row, symbol]));
      let completeness = symbolResult.completeness;
      const warnings = [...symbolResult.warnings];
      const rendered: string[] = [];
      const lines: ListingDocumentLineRef[] = [];
      for (let offset = 0; offset < descriptor.rowCount; offset += PAGE_SIZE) {
        this.requireLatest(key, requestId, signal);
        const start = descriptor.startRow + offset;
        const count = Math.min(PAGE_SIZE, descriptor.rowCount - offset);
        const pageResult = await this.reads.listing(descriptor.context, start, count, {
          signal,
          scope: `${scope}:page`,
        });
        completeness = combineCompleteness(completeness, pageResult.completeness);
        warnings.push(...pageResult.warnings);
        const page = pageResult.value;
        for (const instruction of page.rows) {
          const editorLine = lines.length;
          rendered.push(renderListingLine(instruction, symbolMap.get(instruction.index)));
          lines.push({
            editorLine,
            globalRow: instruction.index,
            semanticRow: semanticRowForInstruction(instruction),
          });
        }
      }
      this.requireLatest(key, requestId, signal);
      if (lines.length !== descriptor.rowCount) {
        throw new TypeError(`Listing transport returned ${lines.length} of ${descriptor.rowCount} requested rows`);
      }
      const projection: ListingDocumentProjection = {
        uri,
        context: { ...descriptor.context },
        startRow: descriptor.startRow,
        rowCount: lines.length,
        content: rendered.join('\n'),
        lines,
        completeness,
        warnings: [...new Set(warnings)],
      };
      this.knownUris.set(key, uri);
      this.invalidated.delete(key);
      this.touchProjection(key, projection);
      while (this.projections.size > MAX_CACHED_LISTING_PROJECTIONS) {
        const oldest = this.projections.keys().next().value as string | undefined;
        if (oldest === undefined) break;
        this.projections.delete(oldest);
        this.invalidated.delete(oldest);
      }
      return projection.content;
    });
  }

  public projection(uri: vscode.Uri): ListingDocumentProjection | undefined {
    return this.projections.get(uri.toString());
  }

  public lineRef(uri: vscode.Uri, editorLine: number): ListingDocumentLineRef | undefined {
    if (!Number.isInteger(editorLine) || editorLine < 0) return undefined;
    const projected = this.projections.get(uri.toString())?.lines[editorLine];
    if (projected !== undefined) return projected;
    const globalRow = listingGlobalRowForEditorLine(uri, editorLine);
    if (globalRow === undefined) return undefined;
    return {
      editorLine,
      globalRow,
      semanticRow: {
        rowId: `${SYNTHETIC_SPACE_ID}:${addressRefForGlobalRow(globalRow).offsetBits}:unit`,
        kind: 'INSTRUCTION',
        location: locationRefForGlobalRow(globalRow),
        fields: {},
      },
    };
  }

  public editorLineForGlobalRow(uri: vscode.Uri, globalRow: number): number | undefined {
    const projection = this.projections.get(uri.toString());
    return projection?.lines.find(line => line.globalRow === globalRow)?.editorLine ??
      listingEditorLineForGlobalRow(uri, globalRow);
  }

  public get cachedProjectionCount(): number {
    return this.projections.size;
  }

  public refresh(uri?: vscode.Uri): void {
    if (uri !== undefined) {
      const key = uri.toString();
      this.invalidated.add(key);
      this.latestByUri.set(key, ++this.requestSequence);
      this.knownUris.set(key, uri);
      this.changed.fire(uri);
      return;
    }
    const uris = [...this.knownUris.values()];
    if (uris.length === 0) uris.push(makeListingUri());
    for (const known of uris) {
      const key = known.toString();
      this.invalidated.add(key);
      this.latestByUri.set(key, ++this.requestSequence);
      this.changed.fire(known);
    }
  }

  public release(uri: vscode.Uri): void {
    const key = uri.toString();
    this.projections.delete(key);
    this.knownUris.delete(key);
    this.invalidated.delete(key);
    this.latestByUri.delete(key);
  }

  private touchProjection(key: string, projection: ListingDocumentProjection): void {
    this.projections.delete(key);
    this.projections.set(key, projection);
  }

  private requireLatest(key: string, requestId: number, signal: AbortSignal): void {
    if (this.disposed || signal.aborted || this.latestByUri.get(key) !== requestId) {
      throw new vscode.CancellationError();
    }
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancellations.dispose();
    this.changed.dispose();
    this.projections.clear();
    this.knownUris.clear();
    this.invalidated.clear();
    this.latestByUri.clear();
  }
}

export interface DecompilerDocumentProjection {
  readonly uri: vscode.Uri;
  readonly context: ViewContext;
  readonly resultId: string;
  readonly result: DecompileResult;
  readonly content: string;
  readonly completeness: NativeReadCompleteness;
  readonly warnings: readonly string[];
}

export class DecompilerDocumentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<vscode.Uri>();
  private readonly cancellations = new NativeReadCancellationPool();
  private readonly cache = new Map<string, DecompilerDocumentProjection>();
  private readonly invalidated = new Set<string>();
  private readonly latestByUri = new Map<string, number>();
  private requestSequence = 0;
  private disposed = false;
  public readonly onDidChange = this.changed.event;

  public constructor(private readonly reads: NativeReadService) {}

  public async provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): Promise<string> {
    return this.cancellations.run(token, async signal => {
      const descriptor = requireDescriptor(this.reads, uri, 'decompiler');
      const key = uri.toString();
      const requestId = ++this.requestSequence;
      this.latestByUri.set(key, requestId);
      const cached = this.cache.get(key);
      if (cached !== undefined && !this.invalidated.has(key)) {
        this.touch(key, cached);
        return cached.content;
      }
      const result = await this.reads.decompile(descriptor.context, descriptor.row, {
        signal,
        scope: `decompiler-document:${key}`,
      });
      if (this.disposed || signal.aborted || this.latestByUri.get(key) !== requestId) {
        throw new vscode.CancellationError();
      }
      const projection: DecompilerDocumentProjection = {
        uri,
        context: { ...result.context },
        resultId: result.resultId,
        result: { ...result.value },
        content: result.value.text,
        completeness: result.completeness,
        warnings: [...result.warnings],
      };
      this.invalidated.delete(key);
      this.touch(key, projection);
      while (this.cache.size > MAX_CACHED_DECOMPILATIONS) {
        const oldest = this.cache.keys().next().value as string | undefined;
        if (oldest === undefined) break;
        this.cache.delete(oldest);
        this.invalidated.delete(oldest);
      }
      return projection.content;
    });
  }

  public projection(uri: vscode.Uri): DecompilerDocumentProjection | undefined {
    return this.cache.get(uri.toString());
  }

  public refresh(uri: vscode.Uri): void {
    const key = uri.toString();
    this.invalidated.add(key);
    this.latestByUri.set(key, ++this.requestSequence);
    this.changed.fire(uri);
  }

  private touch(key: string, projection: DecompilerDocumentProjection): void {
    this.cache.delete(key);
    this.cache.set(key, projection);
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.cancellations.dispose();
    this.changed.dispose();
    this.cache.clear();
    this.invalidated.clear();
    this.latestByUri.clear();
  }
}

interface TokenAtPosition {
  readonly text: string;
  readonly range: vscode.Range;
}

function tokenAt(document: vscode.TextDocument, position: vscode.Position): TokenAtPosition | undefined {
  const text = document.lineAt(position.line).text;
  const pattern = /0x[0-9a-fA-F]+|[A-Za-z_][A-Za-z0-9_]*/g;
  for (const match of text.matchAll(pattern)) {
    const start = match.index;
    const end = start + match[0].length;
    if (position.character >= start && position.character <= end) {
      return {
        text: match[0],
        range: new vscode.Range(position.line, start, position.line, end),
      };
    }
  }
  return undefined;
}

function exactSymbol(symbols: readonly SymbolMatch[], name: string): SymbolMatch | undefined {
  const normalized = name.toLowerCase();
  return symbols.find(symbol => symbol.name.toLowerCase() === normalized);
}

async function symbolLocation(
  reads: NativeReadService,
  context: ViewContext,
  symbol: SymbolMatch,
  signal: AbortSignal,
  scope: string,
): Promise<vscode.Location> {
  const instruction = (await reads.listing(context, symbol.row, 1, { signal, scope })).value.rows[0];
  const line = instruction === undefined ? '' : renderListingLine(instruction, symbol);
  const start = Math.max(0, line.lastIndexOf(symbol.name));
  const uri = makeListingUri(symbol.row);
  const editorLine = listingEditorLineForGlobalRow(uri, symbol.row);
  if (editorLine === undefined) throw new RangeError(`Symbol row ${symbol.row} is outside its listing projection`);
  return new vscode.Location(
    uri,
    new vscode.Range(editorLine, start, editorLine, start + symbol.name.length),
  );
}

export class NativeDocumentSymbolProvider implements vscode.DocumentSymbolProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly listing: ListingLineResolver,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideDocumentSymbols(
    document: vscode.TextDocument,
    token: vscode.CancellationToken,
  ): Promise<vscode.DocumentSymbol[] | undefined> {
    return this.cancellations.run(token, async signal => {
      const descriptor = parseNativeDocumentUri(document.uri);
      if (descriptor?.kind === 'listing') {
        const symbols = await allSymbols(
          this.reads,
          descriptor.context,
          signal,
          `document-symbols:${document.uri.toString()}`,
        );
        return symbols.flatMap(symbol => {
          const editorLine = this.listing.editorLineForGlobalRow(document.uri, symbol.row);
          if (editorLine === undefined) return [];
          const line = document.lineAt(editorLine).text;
          const start = Math.max(0, line.lastIndexOf(symbol.name));
          const selection = new vscode.Range(editorLine, start, editorLine, start + symbol.name.length);
          return [new vscode.DocumentSymbol(
            symbol.name,
            `${symbol.kind} · ${symbol.address} · ${Math.round(symbol.confidence * 100)}% confidence`,
            symbolKind(symbol),
            document.lineAt(editorLine).range,
            selection,
          )];
        });
      }
      if (descriptor?.kind !== 'decompiler') return undefined;

      const result = (await this.reads.decompile(descriptor.context, descriptor.row, {
        signal,
        scope: `document-symbols:${document.uri.toString()}`,
      })).value;
      const functionStart = Math.max(0, document.lineAt(0).text.indexOf(result.functionName));
      const root = new vscode.DocumentSymbol(
        result.functionName,
        result.signature,
        vscode.SymbolKind.Function,
        new vscode.Range(0, 0, document.lineCount - 1, document.lineAt(document.lineCount - 1).text.length),
        new vscode.Range(0, functionStart, 0, functionStart + result.functionName.length),
      );
      const declaration = /\b(?:int|uint32_t|uint64_t|char|bool)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|;)/;
      for (let lineNumber = 1; lineNumber < document.lineCount; lineNumber += 1) {
        const line = document.lineAt(lineNumber);
        const match = declaration.exec(line.text);
        if (match?.[1] === undefined || match.index === undefined) continue;
        const start = line.text.indexOf(match[1], match.index);
        root.children.push(new vscode.DocumentSymbol(
          match[1],
          'recovered local',
          vscode.SymbolKind.Variable,
          line.range,
          new vscode.Range(lineNumber, start, lineNumber, start + match[1].length),
        ));
      }
      return [root];
    });
  }
}

const MNEMONIC_HELP: Readonly<Record<string, string>> = {
  call: 'Transfer control to a procedure and save the return address.',
  cmp: 'Compare operands and update status flags.',
  ja: 'Jump when the unsigned comparison is above.',
  jmp: 'Transfer control unconditionally.',
  jne: 'Jump when the zero flag is clear.',
  lea: 'Load an effective address without dereferencing it.',
  mov: 'Copy the source operand into the destination.',
  movzx: 'Move and zero-extend the source operand.',
  push: 'Push an operand onto the stack.',
  ret: 'Return to the saved caller address.',
  test: 'Compute a bitwise AND for flags without storing the result.',
};

export class NativeHoverProvider implements vscode.HoverProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideHover(
    document: vscode.TextDocument,
    position: vscode.Position,
    cancellationToken: vscode.CancellationToken,
  ): Promise<vscode.Hover | undefined> {
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor === undefined) return undefined;
    const selected = tokenAt(document, position);
    if (selected === undefined) return undefined;

    const help = MNEMONIC_HELP[selected.text.toLowerCase()];
    if (help !== undefined) {
      return new vscode.Hover([`**${selected.text.toUpperCase()}**`, help], selected.range);
    }
    return this.cancellations.run(cancellationToken, async signal => {
      const symbols = await allSymbols(this.reads, descriptor.context, signal, `hover:${document.uri.toString()}`);
      const symbol = exactSymbol(symbols, selected.text);
      if (symbol !== undefined) {
        const markdown = new vscode.MarkdownString();
        markdown.appendMarkdown(`**${symbol.kind}** \`${symbol.name}\`  \n`);
        markdown.appendMarkdown(`Address: \`${symbol.address}\` · Confidence: ${Math.round(symbol.confidence * 100)}%`);
        return new vscode.Hover(markdown, selected.range);
      }

      const row = rowForAddress(selected.text);
      if (row !== undefined) {
        const instruction = (await this.reads.listing(descriptor.context, row, 1, {
          signal,
          scope: `hover:${document.uri.toString()}`,
        })).value.rows[0];
        if (instruction === undefined) return undefined;
        const markdown = new vscode.MarkdownString();
        markdown.appendMarkdown(`**Mapped instruction ${row.toLocaleString()}**  \n`);
        markdown.appendCodeblock(
          `${instruction.address}  ${instruction.bytes}  ${instruction.mnemonic} ${instruction.operands}`,
          'asm',
        );
        return new vscode.Hover(markdown, selected.range);
      }
      return undefined;
    });
  }
}

export class NativeDefinitionProvider implements vscode.DefinitionProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideDefinition(
    document: vscode.TextDocument,
    position: vscode.Position,
    token: vscode.CancellationToken,
  ): Promise<vscode.Definition | undefined> {
    const descriptor = parseNativeDocumentUri(document.uri);
    const selected = tokenAt(document, position);
    if (descriptor === undefined || selected === undefined) return undefined;
    return this.cancellations.run(token, async signal => {
      const scope = `definition:${document.uri.toString()}`;
      const symbols = await allSymbols(this.reads, descriptor.context, signal, scope);
      const symbol = exactSymbol(symbols, selected.text);
      if (symbol !== undefined) {
        return symbolLocation(this.reads, descriptor.context, symbol, signal, scope);
      }

      let row = rowForAddress(selected.text);
      if (row === undefined && selected.text.startsWith('FUN_')) {
        row = rowForAddress(`0x${selected.text.slice(4)}`);
      }
      if (row === undefined) return undefined;
      const uri = makeListingUri(row, descriptor.context);
      const editorLine = listingEditorLineForGlobalRow(uri, row);
      return editorLine === undefined
        ? undefined
        : new vscode.Location(uri, new vscode.Range(editorLine, ADDRESS_START, editorLine, ADDRESS_LENGTH));
    });
  }
}

function occurrenceRanges(line: string, value: string): readonly [number, number][] {
  const ranges: [number, number][] = [];
  const identifier = /^[A-Za-z_][A-Za-z0-9_]*$/.test(value);
  let offset = 0;
  while (offset < line.length) {
    const start = line.indexOf(value, offset);
    if (start < 0) break;
    const end = start + value.length;
    const before = start === 0 ? '' : line[start - 1] ?? '';
    const after = end >= line.length ? '' : line[end] ?? '';
    if (!identifier || (!/[A-Za-z0-9_]/.test(before) && !/[A-Za-z0-9_]/.test(after))) {
      ranges.push([start, end]);
    }
    offset = Math.max(end, start + 1);
  }
  return ranges;
}

async function yieldLocalProjection(signal: AbortSignal): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 0));
  if (signal.aborted) throw new vscode.CancellationError();
}

export class NativeReferenceProvider implements vscode.ReferenceProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly listing: ListingLineResolver,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideReferences(
    document: vscode.TextDocument,
    position: vscode.Position,
    context: vscode.ReferenceContext,
    token: vscode.CancellationToken,
  ): Promise<vscode.Location[] | undefined> {
    const descriptor = parseNativeDocumentUri(document.uri);
    const selected = tokenAt(document, position);
    if (descriptor === undefined || selected === undefined) return undefined;

    return this.cancellations.run(token, async signal => {
      const scope = `references:${document.uri.toString()}`;
      const symbols = await allSymbols(this.reads, descriptor.context, signal, scope);
      const symbol = exactSymbol(symbols, selected.text);
      const locations: vscode.Location[] = [];
      const keys = new Set<string>();
      const add = (location: vscode.Location): void => {
        const key = `${location.uri.toString()}#${location.range.start.line}:${location.range.start.character}`;
        if (!keys.has(key) && locations.length < REFERENCE_LIMIT) {
          keys.add(key);
          locations.push(location);
        }
      };

      if (context.includeDeclaration && symbol !== undefined) {
        add(await symbolLocation(this.reads, descriptor.context, symbol, signal, `${scope}:declaration`));
      }

      for (let lineNumber = 0; lineNumber < document.lineCount && locations.length < REFERENCE_LIMIT; lineNumber += 1) {
        if (signal.aborted) throw new vscode.CancellationError();
        if (lineNumber > 0 && lineNumber % PAGE_SIZE === 0) await yieldLocalProjection(signal);
        const globalRow = descriptor.kind === 'listing'
          ? this.listing.lineRef(document.uri, lineNumber)?.globalRow
          : undefined;
        if (!context.includeDeclaration && symbol !== undefined && globalRow === symbol.row) continue;
        const line = document.lineAt(lineNumber).text;
        for (const [start, end] of occurrenceRanges(line, selected.text)) {
          add(new vscode.Location(document.uri, new vscode.Range(lineNumber, start, lineNumber, end)));
        }
      }

      // A decompiler preview is short. Semantic reference results point into bounded
      // listing projections; never scan or reconstruct the complete program here.
      if (descriptor.kind === 'decompiler' && locations.length < REFERENCE_LIMIT) {
        const referencedRow = symbol?.row ?? rowForAddress(selected.text);
        if (referencedRow !== undefined) {
          const references = (await this.reads.references(
            descriptor.context,
            referencedRow,
            REFERENCE_LIMIT,
            { signal, scope },
          )).value;
          const symbolMap = new Map(symbols.map(candidate => [candidate.row, candidate]));
          for (const reference of references) {
            if (signal.aborted) throw new vscode.CancellationError();
            const instruction = (await this.reads.listing(descriptor.context, reference.sourceRow, 1, {
              signal,
              scope: `${scope}:source`,
            })).value.rows[0];
            if (instruction === undefined) continue;
            const uri = makeListingUri(reference.sourceRow, descriptor.context);
            const editorLine = listingEditorLineForGlobalRow(uri, reference.sourceRow);
            if (editorLine === undefined) continue;
            const line = renderListingLine(instruction, symbolMap.get(instruction.index));
            const occurrence = occurrenceRanges(line, selected.text)[0] ?? [ADDRESS_START, ADDRESS_LENGTH];
            add(new vscode.Location(
              uri,
              new vscode.Range(editorLine, occurrence[0], editorLine, occurrence[1]),
            ));
          }
        }
      }
      return locations;
    });
  }
}

export class NativeCodeLensProvider implements vscode.CodeLensProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly listing: ListingLineResolver,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideCodeLenses(
    document: vscode.TextDocument,
    token: vscode.CancellationToken,
  ): Promise<vscode.CodeLens[] | undefined> {
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor?.kind === 'listing') {
      return this.cancellations.run(token, async signal => {
        const symbols = await allSymbols(
          this.reads,
          descriptor.context,
          signal,
          `codelens:${document.uri.toString()}`,
        );
        const lenses = [new vscode.CodeLens(
          new vscode.Range(0, 0, 0, ADDRESS_LENGTH),
          { title: '$(play) Run analysis', command: 'ghidraex.startAnalysis' },
        )];
        for (const symbol of symbols) {
          if (symbol.kind !== 'Function') continue;
          const editorLine = this.listing.editorLineForGlobalRow(document.uri, symbol.row);
          if (editorLine === undefined) continue;
          lenses.push(new vscode.CodeLens(
            new vscode.Range(editorLine, 0, editorLine, ADDRESS_LENGTH),
            {
              title: '$(symbol-method) Open decompiler',
              command: NATIVE_COMMANDS.openDecompiler,
              arguments: [symbol.row],
            },
          ));
        }
        return lenses;
      });
    }
    if (descriptor?.kind === 'decompiler') {
      return [
        new vscode.CodeLens(new vscode.Range(0, 0, 0, document.lineAt(0).text.length), {
          title: `$(list-tree) Show ${formatAddress(descriptor.row)} in listing`,
          command: NATIVE_COMMANDS.openListingAt,
          arguments: [descriptor.row],
        }),
        new vscode.CodeLens(new vscode.Range(0, 0, 0, document.lineAt(0).text.length), {
          title: '$(play) Run analysis',
          command: 'ghidraex.startAnalysis',
        }),
      ];
    }
    return undefined;
  }
}

export const NATIVE_SEMANTIC_TOKENS_LEGEND = new vscode.SemanticTokensLegend(
  ['namespace', 'type', 'function', 'variable', 'number', 'keyword', 'comment'],
  ['declaration', 'definition', 'readonly', 'defaultLibrary'],
);

interface SemanticSpan {
  readonly start: number;
  readonly length: number;
  readonly type: string;
  readonly modifiers?: readonly string[];
}

function listingSemanticSpans(line: string, symbol?: SymbolMatch): readonly SemanticSpan[] {
  const spans: SemanticSpan[] = [
    { start: ADDRESS_START, length: Math.min(ADDRESS_LENGTH, line.length), type: 'number', modifiers: ['readonly'] },
  ];
  const bytesEnd = Math.min(line.length, BYTES_START + BYTES_WIDTH);
  const bytePattern = /[0-9a-f]{2}/g;
  for (const match of line.slice(BYTES_START, bytesEnd).matchAll(bytePattern)) {
    spans.push({ start: BYTES_START + match.index, length: 2, type: 'number', modifiers: ['readonly'] });
  }
  const mnemonic = line.slice(MNEMONIC_START, MNEMONIC_START + MNEMONIC_WIDTH).trimEnd();
  if (mnemonic.length > 0) spans.push({ start: MNEMONIC_START, length: mnemonic.length, type: 'keyword' });

  const commentStart = line.indexOf(';', OPERANDS_START);
  const operandsEnd = commentStart < 0 ? line.length : commentStart;
  const operandText = line.slice(OPERANDS_START, operandsEnd);
  const operandPattern = /0x[0-9a-fA-F]+|[A-Za-z_][A-Za-z0-9_]*/g;
  for (const match of operandText.matchAll(operandPattern)) {
    const matchSymbol = exactSyntheticSymbolName(match[0], symbol);
    spans.push({
      start: OPERANDS_START + match.index,
      length: match[0].length,
      type: match[0].startsWith('0x') ? 'number' : matchSymbol?.type ?? 'variable',
      ...(matchSymbol?.modifiers === undefined ? {} : { modifiers: matchSymbol.modifiers }),
    });
  }
  if (commentStart >= 0) spans.push({ start: commentStart, length: line.length - commentStart, type: 'comment' });
  return spans;
}

function exactSyntheticSymbolName(
  value: string,
  rowSymbol?: SymbolMatch,
): { readonly type: string; readonly modifiers?: readonly string[] } | undefined {
  if (rowSymbol?.name === value) {
    return { type: semanticTypeForSymbol(rowSymbol), modifiers: ['declaration', 'definition'] };
  }
  return undefined;
}

const C_KEYWORDS = new Set(['if', 'else', 'for', 'while', 'return', 'switch', 'case', 'break', 'continue']);
const C_TYPES = new Set(['int', 'char', 'bool', 'void', 'uint32_t', 'uint64_t', 'Packet', 'Context']);

function decompilerSemanticSpans(line: string): readonly SemanticSpan[] {
  const spans: SemanticSpan[] = [];
  const commentStart = line.indexOf('//');
  const codeEnd = commentStart < 0 ? line.length : commentStart;
  const code = line.slice(0, codeEnd);
  const pattern = /0x[0-9a-fA-F]+|\b\d+\b|[A-Za-z_][A-Za-z0-9_]*/g;
  for (const match of code.matchAll(pattern)) {
    const value = match[0];
    let type = 'variable';
    let modifiers: readonly string[] | undefined;
    if (/^(?:0x[0-9a-fA-F]+|\d+)$/.test(value)) type = 'number';
    else if (C_KEYWORDS.has(value)) type = 'keyword';
    else if (C_TYPES.has(value)) type = 'type';
    else if (code.slice(match.index + value.length).trimStart().startsWith('(')) type = 'function';
    if (type === 'function' && /\b(?:int|void|bool|uint32_t|uint64_t)\s+$/.test(code.slice(0, match.index))) {
      modifiers = ['declaration', 'definition'];
    }
    spans.push({ start: match.index, length: value.length, type, ...(modifiers === undefined ? {} : { modifiers }) });
  }
  if (commentStart >= 0) spans.push({ start: commentStart, length: line.length - commentStart, type: 'comment' });
  return spans;
}

export class NativeRangeSemanticTokensProvider implements vscode.DocumentRangeSemanticTokensProvider {
  public constructor(
    private readonly reads: NativeReadService,
    private readonly listing: ListingLineResolver,
    private readonly cancellations: NativeReadCancellationPool,
  ) {}

  public async provideDocumentRangeSemanticTokens(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): Promise<vscode.SemanticTokens> {
    const descriptor = parseNativeDocumentUri(document.uri);
    const builder = new vscode.SemanticTokensBuilder(NATIVE_SEMANTIC_TOKENS_LEGEND);
    if (descriptor === undefined || document.lineCount === 0) return builder.build();
    return this.cancellations.run(token, async signal => {
      const symbolMap = descriptor.kind === 'listing'
        ? await symbolsByRow(
            this.reads,
            descriptor.context,
            signal,
            `semantic-tokens:${document.uri.toString()}`,
          )
        : undefined;
      const firstLine = Math.max(0, range.start.line);
      const lastLine = Math.min(document.lineCount - 1, range.end.line);
      for (let lineNumber = firstLine; lineNumber <= lastLine; lineNumber += 1) {
        if (signal.aborted) throw new vscode.CancellationError();
        if (lineNumber > firstLine && (lineNumber - firstLine) % PAGE_SIZE === 0) {
          await yieldLocalProjection(signal);
        }
        const line = document.lineAt(lineNumber).text;
        const globalRow = descriptor.kind === 'listing'
          ? this.listing.lineRef(document.uri, lineNumber)?.globalRow
          : undefined;
        const spans = descriptor.kind === 'listing'
          ? listingSemanticSpans(line, globalRow === undefined ? undefined : symbolMap?.get(globalRow))
          : decompilerSemanticSpans(line);
        for (const span of spans) {
          if (span.length > 0) {
            builder.push(
              new vscode.Range(lineNumber, span.start, lineNumber, span.start + span.length),
              span.type,
              span.modifiers,
            );
          }
        }
      }
      return builder.build();
    });
  }
}

export interface NativeDecorationRanges {
  readonly addresses: readonly vscode.Range[];
  readonly bytes: readonly vscode.Range[];
  readonly controlFlow: readonly vscode.Range[];
  readonly comments: readonly vscode.Range[];
}

/** Computes bounded, visible-range decoration targets; callers own decoration styles. */
export function collectNativeDecorationRanges(
  document: vscode.TextDocument,
  visibleRanges: readonly vscode.Range[],
): NativeDecorationRanges {
  const descriptor = parseNativeDocumentUri(document.uri);
  const addresses: vscode.Range[] = [];
  const bytes: vscode.Range[] = [];
  const controlFlow: vscode.Range[] = [];
  const comments: vscode.Range[] = [];
  if (descriptor === undefined) return { addresses, bytes, controlFlow, comments };

  const visited = new Set<number>();
  for (const visible of visibleRanges) {
    const lastLine = Math.min(document.lineCount - 1, visible.end.line);
    for (let lineNumber = Math.max(0, visible.start.line); lineNumber <= lastLine; lineNumber += 1) {
      if (visited.has(lineNumber)) continue;
      visited.add(lineNumber);
      const line = document.lineAt(lineNumber).text;
      const commentStart = line.indexOf(descriptor.kind === 'listing' ? ';' : '//');
      if (commentStart >= 0) comments.push(new vscode.Range(lineNumber, commentStart, lineNumber, line.length));
      if (descriptor.kind !== 'listing') continue;
      addresses.push(new vscode.Range(lineNumber, ADDRESS_START, lineNumber, Math.min(ADDRESS_LENGTH, line.length)));
      bytes.push(new vscode.Range(lineNumber, BYTES_START, lineNumber, Math.min(BYTES_START + BYTES_WIDTH, line.length)));
      const mnemonic = line.slice(MNEMONIC_START, MNEMONIC_START + MNEMONIC_WIDTH).trimEnd();
      if (/^(?:call|j[a-z]*|ret)$/.test(mnemonic)) {
        controlFlow.push(new vscode.Range(lineNumber, MNEMONIC_START, lineNumber, MNEMONIC_START + mnemonic.length));
      }
    }
  }
  return { addresses, bytes, controlFlow, comments };
}
