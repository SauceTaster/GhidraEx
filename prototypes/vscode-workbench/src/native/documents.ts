import * as vscode from 'vscode';
import {
  PAGE_SIZE,
  PROGRAM_ID,
  PROGRAM_REVISION,
  TOTAL_ROWS,
  SyntheticEngine,
  formatAddress,
  rowForAddress,
  type Instruction,
  type SymbolMatch,
} from '../core/engine';

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

export type NativeDocumentDescriptor =
  | {
      readonly kind: 'listing';
      readonly programId: string;
      readonly revision: number;
    }
  | {
      readonly kind: 'decompiler';
      readonly programId: string;
      readonly revision: number;
      readonly row: number;
    };

function revisionQuery(revision: number): string {
  return `revision=${encodeURIComponent(String(revision))}`;
}

/** Returns the canonical URI for the complete native listing document. */
export function makeListingUri(
  programId = PROGRAM_ID,
  revision = PROGRAM_REVISION,
): vscode.Uri {
  return vscode.Uri.from({
    scheme: LISTING_SCHEME,
    authority: programId,
    path: `/${programId}.listing.asm`,
    query: revisionQuery(revision),
  });
}

/** Returns one canonical decompiler document URI per containing function. */
export function makeDecompilerUri(
  row: number,
  programId = PROGRAM_ID,
  revision = PROGRAM_REVISION,
): vscode.Uri {
  if (!Number.isInteger(row) || row < 0 || row >= TOTAL_ROWS) {
    throw new RangeError(`Decompiler row ${row} is outside the program`);
  }
  const functionRow = Math.floor(row / 64) * 64;
  return vscode.Uri.from({
    scheme: DECOMPILER_SCHEME,
    authority: programId,
    path: `/FUN_${formatAddress(functionRow).slice(2)}.c`,
    query: `${revisionQuery(revision)}&row=${functionRow}`,
  });
}

/** Parses a GhidraEx virtual-document URI without trusting its contents. */
export function parseNativeDocumentUri(uri: vscode.Uri): NativeDocumentDescriptor | undefined {
  if (uri.scheme !== LISTING_SCHEME && uri.scheme !== DECOMPILER_SCHEME) {
    return undefined;
  }
  const params = new URLSearchParams(uri.query);
  const revision = Number(params.get('revision'));
  if (!Number.isInteger(revision) || revision < 0 || uri.authority.length === 0) {
    return undefined;
  }
  if (uri.scheme === LISTING_SCHEME) {
    return { kind: 'listing', programId: uri.authority, revision };
  }
  const row = Number(params.get('row'));
  if (!Number.isInteger(row) || row < 0 || row >= TOTAL_ROWS) {
    return undefined;
  }
  return { kind: 'decompiler', programId: uri.authority, revision, row };
}

function requireDescriptor(
  engine: SyntheticEngine,
  uri: vscode.Uri,
  expectedKind: NativeDocumentDescriptor['kind'],
): NativeDocumentDescriptor {
  const descriptor = parseNativeDocumentUri(uri);
  const program = engine.openProgram();
  if (descriptor === undefined || descriptor.kind !== expectedKind ||
      descriptor.programId !== program.id || descriptor.revision !== program.revision) {
    throw vscode.FileSystemError.FileNotFound(uri);
  }
  return descriptor;
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

function allSymbols(engine: SyntheticEngine): readonly SymbolMatch[] {
  return engine.searchSymbols('', 50);
}

function symbolsByRow(engine: SyntheticEngine): ReadonlyMap<number, SymbolMatch> {
  return new Map(allSymbols(engine).map(symbol => [symbol.row, symbol]));
}

export function renderListingLine(instruction: Instruction, symbol?: SymbolMatch): string {
  const notes: string[] = [];
  if (instruction.annotation !== undefined) notes.push(instruction.annotation);
  if (symbol !== undefined) notes.push(`${symbol.kind.toLowerCase()} ${symbol.name}`);
  const code = `${instruction.address}  ${instruction.bytes.padEnd(BYTES_WIDTH)}  ` +
    `${instruction.mnemonic.padEnd(MNEMONIC_WIDTH)}${instruction.operands}`;
  return notes.length === 0 ? code : `${code}  ; ${notes.join(' · ')}`;
}

export class ListingDocumentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<vscode.Uri>();
  private cachedContent: string | undefined;
  public readonly onDidChange = this.changed.event;

  public constructor(private readonly engine: SyntheticEngine) {}

  public provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): string {
    requireDescriptor(this.engine, uri, 'listing');
    if (this.cachedContent !== undefined) return this.cachedContent;

    // Deliberately build page-sized chunks: the extension never materializes an
    // Instruction[100000], while VS Code's text editor owns viewport virtualization.
    const symbolMap = symbolsByRow(this.engine);
    const chunks: string[] = [];
    for (let start = 0; start < TOTAL_ROWS; start += PAGE_SIZE) {
      if (token.isCancellationRequested) throw new vscode.CancellationError();
      const page = this.engine.listing(start, Math.min(PAGE_SIZE, TOTAL_ROWS - start));
      const lines = page.rows.map(instruction => renderListingLine(instruction, symbolMap.get(instruction.index)));
      chunks.push(lines.join('\n'));
    }
    this.cachedContent = chunks.join('\n');
    return this.cachedContent;
  }

  public refresh(uri = makeListingUri()): void {
    this.cachedContent = undefined;
    this.changed.fire(uri);
  }

  public dispose(): void {
    this.changed.dispose();
    this.cachedContent = undefined;
  }
}

export class DecompilerDocumentProvider implements vscode.TextDocumentContentProvider, vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<vscode.Uri>();
  private readonly cache = new Map<number, string>();
  public readonly onDidChange = this.changed.event;

  public constructor(private readonly engine: SyntheticEngine) {}

  public provideTextDocumentContent(uri: vscode.Uri, token: vscode.CancellationToken): string {
    const descriptor = requireDescriptor(this.engine, uri, 'decompiler');
    if (descriptor.kind !== 'decompiler') throw vscode.FileSystemError.FileNotFound(uri);
    if (token.isCancellationRequested) throw new vscode.CancellationError();
    const cached = this.cache.get(descriptor.row);
    if (cached !== undefined) return cached;
    const content = this.engine.decompile(descriptor.row).text;
    this.cache.set(descriptor.row, content);
    return content;
  }

  public refresh(uri: vscode.Uri): void {
    const descriptor = parseNativeDocumentUri(uri);
    if (descriptor?.kind === 'decompiler') this.cache.delete(descriptor.row);
    this.changed.fire(uri);
  }

  public dispose(): void {
    this.changed.dispose();
    this.cache.clear();
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

function exactSymbol(engine: SyntheticEngine, name: string): SymbolMatch | undefined {
  const normalized = name.toLowerCase();
  return engine.searchSymbols(name, 50).find(symbol => symbol.name.toLowerCase() === normalized);
}

function symbolLocation(engine: SyntheticEngine, symbol: SymbolMatch): vscode.Location {
  const instruction = engine.listing(symbol.row, 1).rows[0];
  const line = instruction === undefined ? '' : renderListingLine(instruction, symbol);
  const start = Math.max(0, line.lastIndexOf(symbol.name));
  return new vscode.Location(
    makeListingUri(),
    new vscode.Range(symbol.row, start, symbol.row, start + symbol.name.length),
  );
}

export class NativeDocumentSymbolProvider implements vscode.DocumentSymbolProvider {
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideDocumentSymbols(document: vscode.TextDocument): vscode.DocumentSymbol[] | undefined {
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor?.kind === 'listing') {
      return allSymbols(this.engine).map(symbol => {
        const line = document.lineAt(symbol.row).text;
        const start = Math.max(0, line.lastIndexOf(symbol.name));
        const selection = new vscode.Range(symbol.row, start, symbol.row, start + symbol.name.length);
        return new vscode.DocumentSymbol(
          symbol.name,
          `${symbol.kind} · ${symbol.address} · ${Math.round(symbol.confidence * 100)}% confidence`,
          symbolKind(symbol),
          document.lineAt(symbol.row).range,
          selection,
        );
      });
    }
    if (descriptor?.kind !== 'decompiler') return undefined;

    const result = this.engine.decompile(descriptor.row);
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
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideHover(document: vscode.TextDocument, position: vscode.Position): vscode.Hover | undefined {
    if (parseNativeDocumentUri(document.uri) === undefined) return undefined;
    const token = tokenAt(document, position);
    if (token === undefined) return undefined;

    const symbol = exactSymbol(this.engine, token.text);
    if (symbol !== undefined) {
      const markdown = new vscode.MarkdownString();
      markdown.appendMarkdown(`**${symbol.kind}** \`${symbol.name}\`  \n`);
      markdown.appendMarkdown(`Address: \`${symbol.address}\` · Confidence: ${Math.round(symbol.confidence * 100)}%`);
      return new vscode.Hover(markdown, token.range);
    }

    const row = rowForAddress(token.text);
    if (row !== undefined) {
      const instruction = this.engine.listing(row, 1).rows[0];
      if (instruction === undefined) return undefined;
      const markdown = new vscode.MarkdownString();
      markdown.appendMarkdown(`**Mapped instruction ${row.toLocaleString()}**  \n`);
      markdown.appendCodeblock(`${instruction.address}  ${instruction.bytes}  ${instruction.mnemonic} ${instruction.operands}`, 'asm');
      return new vscode.Hover(markdown, token.range);
    }

    const help = MNEMONIC_HELP[token.text.toLowerCase()];
    if (help !== undefined) {
      return new vscode.Hover([`**${token.text.toUpperCase()}**`, help], token.range);
    }
    return undefined;
  }
}

export class NativeDefinitionProvider implements vscode.DefinitionProvider {
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideDefinition(document: vscode.TextDocument, position: vscode.Position): vscode.Definition | undefined {
    if (parseNativeDocumentUri(document.uri) === undefined) return undefined;
    const token = tokenAt(document, position);
    if (token === undefined) return undefined;
    const symbol = exactSymbol(this.engine, token.text);
    if (symbol !== undefined) return symbolLocation(this.engine, symbol);

    let row = rowForAddress(token.text);
    if (row === undefined && token.text.startsWith('FUN_')) {
      row = rowForAddress(`0x${token.text.slice(4)}`);
    }
    return row === undefined
      ? undefined
      : new vscode.Location(makeListingUri(), new vscode.Range(row, ADDRESS_START, row, ADDRESS_LENGTH));
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

export class NativeReferenceProvider implements vscode.ReferenceProvider {
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideReferences(
    document: vscode.TextDocument,
    position: vscode.Position,
    context: vscode.ReferenceContext,
    token: vscode.CancellationToken,
  ): vscode.Location[] | undefined {
    const descriptor = parseNativeDocumentUri(document.uri);
    const selected = tokenAt(document, position);
    if (descriptor === undefined || selected === undefined) return undefined;

    const symbol = exactSymbol(this.engine, selected.text);
    const locations: vscode.Location[] = [];
    const keys = new Set<string>();
    const add = (location: vscode.Location): void => {
      const key = `${location.uri.toString()}#${location.range.start.line}:${location.range.start.character}`;
      if (!keys.has(key) && locations.length < REFERENCE_LIMIT) {
        keys.add(key);
        locations.push(location);
      }
    };

    if (context.includeDeclaration && symbol !== undefined) add(symbolLocation(this.engine, symbol));

    for (let lineNumber = 0; lineNumber < document.lineCount && locations.length < REFERENCE_LIMIT; lineNumber += 1) {
      if (token.isCancellationRequested) return locations;
      if (!context.includeDeclaration && symbol !== undefined && descriptor.kind === 'listing' && lineNumber === symbol.row) continue;
      const line = document.lineAt(lineNumber).text;
      for (const [start, end] of occurrenceRanges(line, selected.text)) {
        add(new vscode.Location(document.uri, new vscode.Range(lineNumber, start, lineNumber, end)));
      }
    }

    // A decompiler preview is short; add call/data references from the complete
    // listing without opening or duplicating the 100k-line virtual document.
    if (descriptor.kind === 'decompiler' && locations.length < REFERENCE_LIMIT) {
      const symbolMap = symbolsByRow(this.engine);
      for (let start = 0; start < TOTAL_ROWS && locations.length < REFERENCE_LIMIT; start += PAGE_SIZE) {
        if (token.isCancellationRequested) return locations;
        const page = this.engine.listing(start, Math.min(PAGE_SIZE, TOTAL_ROWS - start));
        for (const instruction of page.rows) {
          const line = renderListingLine(instruction, symbolMap.get(instruction.index));
          for (const [matchStart, matchEnd] of occurrenceRanges(line, selected.text)) {
            if (!context.includeDeclaration && symbol?.row === instruction.index) continue;
            add(new vscode.Location(
              makeListingUri(),
              new vscode.Range(instruction.index, matchStart, instruction.index, matchEnd),
            ));
          }
          if (locations.length >= REFERENCE_LIMIT) break;
        }
      }
    }
    return locations;
  }
}

export class NativeCodeLensProvider implements vscode.CodeLensProvider {
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideCodeLenses(document: vscode.TextDocument): vscode.CodeLens[] | undefined {
    const descriptor = parseNativeDocumentUri(document.uri);
    if (descriptor?.kind === 'listing') {
      const lenses = [new vscode.CodeLens(
        new vscode.Range(0, 0, 0, ADDRESS_LENGTH),
        { title: '$(play) Run analysis', command: 'ghidraex.startAnalysis' },
      )];
      for (const symbol of allSymbols(this.engine)) {
        if (symbol.kind !== 'Function') continue;
        lenses.push(new vscode.CodeLens(
          new vscode.Range(symbol.row, 0, symbol.row, ADDRESS_LENGTH),
          {
            title: '$(symbol-method) Open decompiler',
            command: NATIVE_COMMANDS.openDecompiler,
            arguments: [symbol.row],
          },
        ));
      }
      return lenses;
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
  public constructor(private readonly engine: SyntheticEngine) {}

  public provideDocumentRangeSemanticTokens(
    document: vscode.TextDocument,
    range: vscode.Range,
    token: vscode.CancellationToken,
  ): vscode.SemanticTokens {
    const descriptor = parseNativeDocumentUri(document.uri);
    const builder = new vscode.SemanticTokensBuilder(NATIVE_SEMANTIC_TOKENS_LEGEND);
    if (descriptor === undefined || document.lineCount === 0) return builder.build();
    const symbolMap = descriptor.kind === 'listing' ? symbolsByRow(this.engine) : undefined;
    const firstLine = Math.max(0, range.start.line);
    const lastLine = Math.min(document.lineCount - 1, range.end.line);
    for (let lineNumber = firstLine; lineNumber <= lastLine; lineNumber += 1) {
      if (token.isCancellationRequested) break;
      const line = document.lineAt(lineNumber).text;
      const spans = descriptor.kind === 'listing'
        ? listingSemanticSpans(line, symbolMap?.get(lineNumber))
        : decompilerSemanticSpans(line);
      for (const span of spans) {
        if (span.length > 0) {
          builder.push(new vscode.Range(lineNumber, span.start, lineNumber, span.start + span.length), span.type, span.modifiers);
        }
      }
    }
    return builder.build();
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
