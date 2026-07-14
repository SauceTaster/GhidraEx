import {
  PAGE_SIZE,
  SyntheticEngine,
  type DecompileResult,
  type EvidenceRecord,
  type ListingPage,
  type ProgramSummary,
  type ReferenceMatch,
  type SymbolMatch,
} from './engine';
import type { ViewContext } from './viewState';

export type NativeReadKind = 'program' | 'listing' | 'symbols' | 'decompiler' | 'references' | 'evidence';
export type NativeReadFreshness = 'loading' | 'current' | 'partial' | 'stale' | 'error' | 'cancelled' | 'resyncing';
export type NativeReadCompleteness = 'COMPLETE' | 'PARTIAL' | 'TRUNCATED';

export interface NativeReadActivity {
  readonly kind: NativeReadKind | 'all';
  readonly scope: string;
  readonly context: ViewContext;
  readonly freshness: NativeReadFreshness;
  readonly detail: string;
}

export interface ContextualReadResult<T> {
  readonly context: ViewContext;
  readonly resultId: string;
  readonly value: T;
  readonly completeness: NativeReadCompleteness;
  readonly warnings: readonly string[];
}

export interface NativeReadOptions {
  readonly signal?: AbortSignal;
  /** Latest request wins within one scope, even when semantic cache keys differ. */
  readonly scope?: string;
}

export interface NativeReadTransport {
  program(context: ViewContext, signal: AbortSignal): Promise<ContextualReadResult<ProgramSummary>>;
  listing(
    context: ViewContext,
    start: number,
    count: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<ListingPage>>;
  symbols(
    context: ViewContext,
    query: string,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly SymbolMatch[]>>;
  decompile(
    context: ViewContext,
    row: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<DecompileResult>>;
  references(
    context: ViewContext,
    row: number,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly ReferenceMatch[]>>;
  evidence(
    context: ViewContext,
    row: number,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly EvidenceRecord[]>>;
}

export interface NativeReadCacheCounts {
  readonly program: number;
  readonly listing: number;
  readonly symbols: number;
  readonly decompiler: number;
  readonly references: number;
  readonly evidence: number;
}

export interface NativeReadCacheLimits {
  readonly program: number;
  readonly listing: number;
  readonly symbols: number;
  readonly decompiler: number;
  readonly references: number;
  readonly evidence: number;
}

const DEFAULT_CACHE_LIMITS: NativeReadCacheLimits = {
  program: 2,
  listing: 32,
  symbols: 16,
  decompiler: 64,
  references: 32,
  evidence: 32,
};

export class NativeReadCancelledError extends Error {
  public constructor(message = 'Native read cancelled') {
    super(message);
    this.name = 'NativeReadCancelledError';
  }
}

export class NativeReadSupersededError extends NativeReadCancelledError {
  public constructor() {
    super('Native read superseded by a newer request');
    this.name = 'NativeReadSupersededError';
  }
}

export class NativeReadIdentityError extends Error {
  public constructor(message: string) {
    super(message);
    this.name = 'NativeReadIdentityError';
  }
}

export class NativeReadResyncError extends Error {
  public constructor(message = 'A validated snapshot is required before native reads may resume') {
    super(message);
    this.name = 'NativeReadResyncError';
  }
}

class BoundedLru<T> {
  private readonly entries = new Map<string, T>();

  public constructor(private readonly limit: number) {
    if (!Number.isInteger(limit) || limit < 1) throw new RangeError('Cache limit must be positive');
  }

  public get(key: string): T | undefined {
    const value = this.entries.get(key);
    if (value === undefined) return undefined;
    this.entries.delete(key);
    this.entries.set(key, value);
    return value;
  }

  public set(key: string, value: T): void {
    this.entries.delete(key);
    this.entries.set(key, value);
    while (this.entries.size > this.limit) {
      const oldest = this.entries.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }

  public clear(): void {
    this.entries.clear();
  }

  public get size(): number {
    return this.entries.size;
  }
}

function copyContext(context: ViewContext): ViewContext {
  return { ...context };
}

export function sameReadContext(left: ViewContext, right: ViewContext): boolean {
  return left.runtimeId === right.runtimeId &&
    left.runtimeEpoch === right.runtimeEpoch &&
    left.programId === right.programId &&
    left.contentGeneration === right.contentGeneration;
}

function tupleKey(...parts: readonly (string | number)[]): string {
  return JSON.stringify(parts);
}

/** Collision-free exact identity encoding suitable for cache and correlation keys. */
export function nativeReadContextKey(context: ViewContext): string {
  return tupleKey(context.runtimeId, context.runtimeEpoch, context.programId, context.contentGeneration);
}

function requireRow(row: number): void {
  if (!Number.isSafeInteger(row) || row < 0) throw new RangeError('Row must be a non-negative safe integer');
}

function throwIfAborted(signal: AbortSignal): void {
  if (signal.aborted) throw new NativeReadCancelledError();
}

function cloneResult<T>(result: ContextualReadResult<T>, clone: (value: T) => T): ContextualReadResult<T> {
  return {
    context: copyContext(result.context),
    resultId: result.resultId,
    value: clone(result.value),
    completeness: result.completeness,
    warnings: [...result.warnings],
  };
}

const MAX_RESULT_ID_LENGTH = 512;
const MAX_SYMBOL_QUERY_LENGTH = 4_096;
const MAX_WARNING_COUNT = 64;
const MAX_WARNING_LENGTH = 1_024;
const MAX_DECOMPILER_TEXT_LENGTH = 2 * 1_024 * 1_024;
const MAX_AGGREGATE_METADATA_LENGTH = 512 * 1_024;
const MAX_ACTIVITY_DETAIL_LENGTH = 2_048;

function requireBoundedText(value: unknown, name: string, maximum: number, allowBlank = false): asserts value is string {
  if (typeof value !== 'string' || (!allowBlank && value.trim().length === 0) || value.length > maximum) {
    throw new TypeError(`${name} must be ${allowBlank ? 'a' : 'a non-blank'} string of at most ${maximum} characters`);
  }
}

function requireSafeNonNegative(value: unknown, name: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`${name} must be a non-negative safe integer`);
  }
}

function requireConfidence(value: unknown, name: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1) {
    throw new TypeError(`${name} must be a finite value between 0 and 1`);
  }
}

function validateContext(context: ViewContext): void {
  requireBoundedText(context.runtimeId, 'runtimeId', 256);
  requireBoundedText(context.programId, 'programId', 512);
  requireSafeNonNegative(context.runtimeEpoch, 'runtimeEpoch');
  requireSafeNonNegative(context.contentGeneration, 'contentGeneration');
}

function validateEnvelope<T>(result: ContextualReadResult<T>): void {
  if (typeof result !== 'object' || result === null) throw new TypeError('Transport result must be an object');
  validateContext(result.context);
  requireBoundedText(result.resultId, 'resultId', MAX_RESULT_ID_LENGTH);
  if (result.completeness !== 'COMPLETE' && result.completeness !== 'PARTIAL' && result.completeness !== 'TRUNCATED') {
    throw new TypeError('Transport result has an unknown completeness value');
  }
  if (!Array.isArray(result.warnings) || result.warnings.length > MAX_WARNING_COUNT) {
    throw new TypeError(`warnings must contain at most ${MAX_WARNING_COUNT} strings`);
  }
  let aggregate = 0;
  for (const warning of result.warnings) {
    requireBoundedText(warning, 'warning', MAX_WARNING_LENGTH, true);
    aggregate += warning.length;
  }
  if (aggregate > 16 * 1_024) throw new TypeError('Aggregate warning text exceeds 16384 characters');
}

function validateProgramSummary(value: ProgramSummary): void {
  requireBoundedText(value.id, 'program.id', 512);
  requireBoundedText(value.name, 'program.name', 512);
  requireBoundedText(value.format, 'program.format', 256);
  requireBoundedText(value.language, 'program.language', 256);
  requireSafeNonNegative(value.revision, 'program.revision');
  requireBoundedText(value.imageBase, 'program.imageBase', 128);
  requireSafeNonNegative(value.instructionCount, 'program.instructionCount');
  if (value.status !== 'partially-analyzed' && value.status !== 'ready') {
    throw new TypeError('program.status is invalid');
  }
}

function validateListingPage(value: ListingPage, start: number, count: number): void {
  requireSafeNonNegative(value.start, 'listing.start');
  requireSafeNonNegative(value.count, 'listing.count');
  requireSafeNonNegative(value.total, 'listing.total');
  if (!Array.isArray(value.rows) || value.start !== start || value.count !== value.rows.length ||
      value.rows.length > count) {
    throw new TypeError('Listing transport returned an invalid bounded page');
  }
  let aggregate = 0;
  for (const [index, row] of value.rows.entries()) {
    requireSafeNonNegative(row.index, 'instruction.index');
    if (row.index !== start + index) throw new TypeError('Listing rows are not contiguous');
    requireBoundedText(row.address, 'instruction.address', 128);
    requireBoundedText(row.bytes, 'instruction.bytes', 512, true);
    requireBoundedText(row.mnemonic, 'instruction.mnemonic', 128);
    requireBoundedText(row.operands, 'instruction.operands', 2_048, true);
    if (row.annotation !== undefined) requireBoundedText(row.annotation, 'instruction.annotation', 2_048, true);
    aggregate += row.address.length + row.bytes.length + row.mnemonic.length + row.operands.length +
      (row.annotation?.length ?? 0);
  }
  if (aggregate > MAX_AGGREGATE_METADATA_LENGTH) throw new TypeError('Aggregate listing metadata exceeds its budget');
}

function validateSymbols(value: readonly SymbolMatch[], limit: number): void {
  if (!Array.isArray(value) || value.length > limit) throw new TypeError('Symbol transport exceeded the requested limit');
  let aggregate = 0;
  for (const symbol of value) {
    requireBoundedText(symbol.name, 'symbol.name', 1_024);
    if (symbol.kind !== 'Function' && symbol.kind !== 'Label' && symbol.kind !== 'Import') {
      throw new TypeError('symbol.kind is invalid');
    }
    requireSafeNonNegative(symbol.row, 'symbol.row');
    requireBoundedText(symbol.address, 'symbol.address', 128);
    requireConfidence(symbol.confidence, 'symbol.confidence');
    aggregate += symbol.name.length + symbol.address.length;
  }
  if (aggregate > MAX_AGGREGATE_METADATA_LENGTH) throw new TypeError('Aggregate symbol metadata exceeds its budget');
}

function validateDecompiler(value: DecompileResult, requestedRow: number): void {
  requireBoundedText(value.functionName, 'decompiler.functionName', 1_024);
  requireBoundedText(value.signature, 'decompiler.signature', 8_192);
  requireBoundedText(value.text, 'decompiler.text', MAX_DECOMPILER_TEXT_LENGTH, true);
  requireSafeNonNegative(value.row, 'decompiler.row');
  if (value.row > requestedRow) throw new TypeError('Decompiler containing row is after the requested row');
  requireBoundedText(value.address, 'decompiler.address', 128);
}

function validateReferences(value: readonly ReferenceMatch[], limit: number): void {
  if (!Array.isArray(value) || value.length > limit) throw new TypeError('Reference transport exceeded the requested limit');
  let aggregate = 0;
  for (const reference of value) {
    requireSafeNonNegative(reference.sourceRow, 'reference.sourceRow');
    requireBoundedText(reference.sourceAddress, 'reference.sourceAddress', 128);
    if (reference.kind !== 'CALL' && reference.kind !== 'READ' && reference.kind !== 'WRITE') {
      throw new TypeError('reference.kind is invalid');
    }
    requireBoundedText(reference.functionName, 'reference.functionName', 1_024);
    aggregate += reference.sourceAddress.length + reference.functionName.length;
  }
  if (aggregate > MAX_AGGREGATE_METADATA_LENGTH) throw new TypeError('Aggregate reference metadata exceeds its budget');
}

function validateEvidence(value: readonly EvidenceRecord[], limit: number): void {
  if (!Array.isArray(value) || value.length > limit) throw new TypeError('Evidence transport exceeded the requested limit');
  let aggregate = 0;
  for (const evidence of value) {
    requireBoundedText(evidence.id, 'evidence.id', 512);
    requireSafeNonNegative(evidence.row, 'evidence.row');
    requireBoundedText(evidence.address, 'evidence.address', 128);
    requireBoundedText(evidence.title, 'evidence.title', 2_048);
    requireBoundedText(evidence.detail, 'evidence.detail', 32_768, true);
    requireConfidence(evidence.confidence, 'evidence.confidence');
    if (evidence.classification !== 'fact' && evidence.classification !== 'hypothesis') {
      throw new TypeError('evidence.classification is invalid');
    }
    aggregate += evidence.id.length + evidence.address.length + evidence.title.length + evidence.detail.length;
  }
  if (aggregate > MAX_AGGREGATE_METADATA_LENGTH) throw new TypeError('Aggregate evidence metadata exceeds its budget');
}

type ActivityListener = (activity: NativeReadActivity) => void;

export class NativeReadService {
  private readonly programCache: BoundedLru<ContextualReadResult<ProgramSummary>>;
  private readonly listingCache: BoundedLru<ContextualReadResult<ListingPage>>;
  private readonly symbolsCache: BoundedLru<ContextualReadResult<readonly SymbolMatch[]>>;
  private readonly decompilerCache: BoundedLru<ContextualReadResult<DecompileResult>>;
  private readonly referencesCache: BoundedLru<ContextualReadResult<readonly ReferenceMatch[]>>;
  private readonly evidenceCache: BoundedLru<ContextualReadResult<readonly EvidenceRecord[]>>;
  private readonly latestByScope = new Map<string, number>();
  private readonly inflightByScope = new Map<string, { readonly requestId: number; readonly controller: AbortController }>();
  private readonly listeners = new Set<ActivityListener>();
  private requestSequence = 0;
  private resyncRequired = false;
  private disposed = false;

  public constructor(
    private readonly transport: NativeReadTransport,
    private contextState: ViewContext,
    limits: NativeReadCacheLimits = DEFAULT_CACHE_LIMITS,
  ) {
    validateContext(contextState);
    this.contextState = copyContext(contextState);
    this.programCache = new BoundedLru(limits.program);
    this.listingCache = new BoundedLru(limits.listing);
    this.symbolsCache = new BoundedLru(limits.symbols);
    this.decompilerCache = new BoundedLru(limits.decompiler);
    this.referencesCache = new BoundedLru(limits.references);
    this.evidenceCache = new BoundedLru(limits.evidence);
  }

  public get context(): ViewContext {
    return copyContext(this.contextState);
  }

  public onDidChangeActivity(listener: ActivityListener): { dispose(): void } {
    this.listeners.add(listener);
    return { dispose: () => this.listeners.delete(listener) };
  }

  public updateContext(context: ViewContext): void {
    this.applySnapshot(context);
  }

  public applySnapshot(context: ViewContext): void {
    this.ensureOpen();
    validateContext(context);
    this.requireNonRegressiveSnapshot(context);
    const changed = !sameReadContext(context, this.contextState);
    this.contextState = copyContext(context);
    this.resyncRequired = false;
    if (changed) this.invalidate('Runtime/program/generation context changed');
    else this.invalidate('Validated snapshot restored async read authority');
  }

  public enterResync(detail = 'Event sequence gap; validated snapshot required'): void {
    this.ensureOpen();
    this.resyncRequired = true;
    this.abortInflight();
    this.clearCaches();
    this.latestByScope.clear();
    this.emit({
      kind: 'all',
      scope: 'all',
      context: this.context,
      freshness: 'resyncing',
      detail,
    });
  }

  public get authoritativeReadsAllowed(): boolean {
    return !this.disposed && !this.resyncRequired;
  }

  public async program(options: NativeReadOptions = {}): Promise<ContextualReadResult<ProgramSummary>> {
    const context = this.context;
    return this.perform(
      'program',
      nativeReadContextKey(context),
      this.programCache,
      options,
      signal => this.transport.program(context, signal),
      value => ({ ...value }),
      value => {
        validateProgramSummary(value);
        if (value.id !== context.programId || value.revision !== context.contentGeneration) {
          throw new NativeReadIdentityError('Program summary does not match the requested context');
        }
      },
    );
  }

  public async listing(
    context: ViewContext,
    start: number,
    count: number,
    options: NativeReadOptions = {},
  ): Promise<ContextualReadResult<ListingPage>> {
    this.requireCurrentContext(context);
    requireRow(start);
    if (!Number.isInteger(count) || count < 1 || count > PAGE_SIZE) {
      throw new RangeError(`Listing count must be between 1 and ${PAGE_SIZE}`);
    }
    const key = tupleKey(nativeReadContextKey(context), start, count);
    return this.perform(
      'listing', key, this.listingCache, options,
      signal => this.transport.listing(copyContext(context), start, count, signal),
      value => ({ ...value, rows: value.rows.map(row => ({ ...row })) }),
      value => validateListingPage(value, start, count),
    );
  }

  public async symbols(
    context: ViewContext,
    query: string,
    limit: number,
    options: NativeReadOptions = {},
  ): Promise<ContextualReadResult<readonly SymbolMatch[]>> {
    this.requireCurrentContext(context);
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw new RangeError('Symbol limit must be 1..50');
    requireBoundedText(query, 'symbol query', MAX_SYMBOL_QUERY_LENGTH, true);
    const normalized = query.trim().toLowerCase();
    const key = tupleKey(nativeReadContextKey(context), normalized, limit);
    return this.perform(
      'symbols', key, this.symbolsCache, options,
      signal => this.transport.symbols(copyContext(context), normalized, limit, signal),
      value => value.map(symbol => ({ ...symbol })),
      value => validateSymbols(value, limit),
    );
  }

  public async decompile(
    context: ViewContext,
    row: number,
    options: NativeReadOptions = {},
  ): Promise<ContextualReadResult<DecompileResult>> {
    this.requireCurrentContext(context);
    requireRow(row);
    const key = tupleKey(nativeReadContextKey(context), row);
    return this.perform(
      'decompiler', key, this.decompilerCache, options,
      signal => this.transport.decompile(copyContext(context), row, signal),
      value => ({ ...value }),
      value => validateDecompiler(value, row),
    );
  }

  public async references(
    context: ViewContext,
    row: number,
    limit: number,
    options: NativeReadOptions = {},
  ): Promise<ContextualReadResult<readonly ReferenceMatch[]>> {
    this.requireCurrentContext(context);
    requireRow(row);
    if (!Number.isInteger(limit) || limit < 1 || limit > 256) throw new RangeError('Reference limit must be 1..256');
    const key = tupleKey(nativeReadContextKey(context), row, limit);
    return this.perform(
      'references', key, this.referencesCache, options,
      signal => this.transport.references(copyContext(context), row, limit, signal),
      value => value.map(reference => ({ ...reference })),
      value => validateReferences(value, limit),
    );
  }

  public async evidence(
    context: ViewContext,
    row: number,
    limit: number,
    options: NativeReadOptions = {},
  ): Promise<ContextualReadResult<readonly EvidenceRecord[]>> {
    this.requireCurrentContext(context);
    requireRow(row);
    if (!Number.isInteger(limit) || limit < 1 || limit > 64) throw new RangeError('Evidence limit must be 1..64');
    const key = tupleKey(nativeReadContextKey(context), row, limit);
    return this.perform(
      'evidence', key, this.evidenceCache, options,
      signal => this.transport.evidence(copyContext(context), row, limit, signal),
      value => value.map(evidence => ({ ...evidence })),
      value => validateEvidence(value, limit),
    );
  }

  public invalidate(detail = 'Read caches invalidated'): void {
    this.ensureOpen();
    this.abortInflight();
    this.clearCaches();
    this.latestByScope.clear();
    this.emit({
      kind: 'all',
      scope: 'all',
      context: this.context,
      freshness: 'stale',
      detail,
    });
  }

  public get cacheCounts(): NativeReadCacheCounts {
    return {
      program: this.programCache.size,
      listing: this.listingCache.size,
      symbols: this.symbolsCache.size,
      decompiler: this.decompilerCache.size,
      references: this.referencesCache.size,
      evidence: this.evidenceCache.size,
    };
  }

  public dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.requestSequence += 1;
    this.abortInflight();
    this.latestByScope.clear();
    this.clearCaches();
    this.listeners.clear();
  }

  private async perform<T>(
    kind: NativeReadKind,
    semanticKey: string,
    cache: BoundedLru<ContextualReadResult<T>>,
    options: NativeReadOptions,
    load: (signal: AbortSignal) => Promise<ContextualReadResult<T>>,
    clone: (value: T) => T,
    validate: (value: T) => void,
  ): Promise<ContextualReadResult<T>> {
    this.ensureOpen();
    if (this.resyncRequired) {
      const activity: NativeReadActivity = {
        kind,
        scope: tupleKey(kind, options.scope ?? semanticKey),
        context: this.context,
        freshness: 'resyncing',
        detail: 'Validated snapshot required before reads may resume',
      };
      this.emit(activity);
      throw new NativeReadResyncError();
    }
    const callerSignal = options.signal;
    if (callerSignal !== undefined) throwIfAborted(callerSignal);
    const expectedContext = this.context;
    const scope = tupleKey(kind, options.scope ?? semanticKey);
    const requestId = ++this.requestSequence;
    this.inflightByScope.get(scope)?.controller.abort();
    this.latestByScope.set(scope, requestId);
    const cached = cache.get(semanticKey);
    if (cached !== undefined) {
      this.emit({
        kind,
        scope,
        context: expectedContext,
        freshness: cached.completeness === 'COMPLETE' ? 'current' : 'partial',
        detail: cached.completeness === 'COMPLETE'
          ? 'Context-qualified cache hit'
          : `Cached ${cached.completeness.toLowerCase()} result${cached.warnings.length === 0 ? '' : ` · ${cached.warnings.join(' · ')}`}`,
      });
      return cloneResult(cached, clone);
    }
    const controller = new AbortController();
    const abortFromCaller = (): void => controller.abort();
    callerSignal?.addEventListener('abort', abortFromCaller, { once: true });
    if (callerSignal?.aborted === true) controller.abort();
    this.inflightByScope.set(scope, { requestId, controller });
    this.emit({ kind, scope, context: expectedContext, freshness: 'loading', detail: 'Awaiting async transport' });
    try {
      const untrusted = await load(controller.signal);
      throwIfAborted(controller.signal);
      if (this.disposed) throw new NativeReadCancelledError('Native read service disposed');
      if (this.latestByScope.get(scope) !== requestId) throw new NativeReadSupersededError();
      validateEnvelope(untrusted);
      if (!sameReadContext(expectedContext, this.contextState) || !sameReadContext(expectedContext, untrusted.context)) {
        throw new NativeReadIdentityError('Async result does not match the active runtime/program/generation context');
      }
      validate(untrusted.value);
      const safe = cloneResult(untrusted, clone);
      cache.set(semanticKey, safe);
      this.emit({
        kind,
        scope,
        context: expectedContext,
        freshness: safe.completeness === 'COMPLETE' ? 'current' : 'partial',
        detail: safe.warnings.length === 0
          ? `${safe.resultId} · ${safe.completeness}`
          : `${safe.resultId} · ${safe.completeness} · ${safe.warnings.join(' · ')}`,
      });
      return cloneResult(safe, clone);
    } catch (error) {
      const acceptedRequestId = this.latestByScope.get(scope);
      const superseded = acceptedRequestId !== undefined && acceptedRequestId !== requestId;
      const cancelled = error instanceof NativeReadCancelledError || controller.signal.aborted || this.disposed || superseded;
      this.emit({
        kind,
        scope,
        context: expectedContext,
        freshness: cancelled ? 'cancelled' : 'error',
        detail: error instanceof Error ? error.message : String(error),
      });
      if (superseded && !(error instanceof NativeReadSupersededError)) throw new NativeReadSupersededError();
      if (cancelled && !(error instanceof NativeReadCancelledError)) throw new NativeReadCancelledError();
      throw error;
    } finally {
      callerSignal?.removeEventListener('abort', abortFromCaller);
      if (this.inflightByScope.get(scope)?.requestId === requestId) this.inflightByScope.delete(scope);
    }
  }

  private requireCurrentContext(context: ViewContext): void {
    if (!sameReadContext(context, this.contextState)) {
      throw new NativeReadIdentityError('Read request does not match the active context');
    }
  }

  private emit(activity: NativeReadActivity): void {
    const safe = {
      ...activity,
      context: copyContext(activity.context),
      detail: activity.detail.slice(0, MAX_ACTIVITY_DETAIL_LENGTH),
    };
    for (const listener of this.listeners) {
      try {
        listener(safe);
      } catch {
        // Presentation listeners are observational and may not break an authoritative read.
      }
    }
  }

  private requireNonRegressiveSnapshot(next: ViewContext): void {
    const current = this.contextState;
    if (next.runtimeId !== current.runtimeId) return;
    if (next.runtimeEpoch < current.runtimeEpoch) {
      throw new NativeReadIdentityError('Snapshot runtime epoch regressed within the same runtime incarnation');
    }
    if (next.runtimeEpoch === current.runtimeEpoch && next.programId === current.programId &&
      next.contentGeneration < current.contentGeneration) {
      throw new NativeReadIdentityError('Snapshot content generation regressed for the active program incarnation');
    }
  }

  private abortInflight(): void {
    for (const pending of this.inflightByScope.values()) pending.controller.abort();
    this.inflightByScope.clear();
  }

  private clearCaches(): void {
    this.programCache.clear();
    this.listingCache.clear();
    this.symbolsCache.clear();
    this.decompilerCache.clear();
    this.referencesCache.clear();
    this.evidenceCache.clear();
  }

  private ensureOpen(): void {
    if (this.disposed) throw new NativeReadCancelledError('Native read service disposed');
  }
}

function abortableYield(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new NativeReadCancelledError());
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', cancel);
      resolve();
    }, 0);
    const cancel = (): void => {
      clearTimeout(timer);
      reject(new NativeReadCancelledError());
    };
    signal.addEventListener('abort', cancel, { once: true });
  });
}

/** Async fixture transport with the same boundary a JVM/gRPC/Web endpoint would implement. */
export class SyntheticReadTransport implements NativeReadTransport {
  private resultSequence = 0;

  public constructor(private readonly engine: SyntheticEngine) {}

  public async program(context: ViewContext, signal: AbortSignal): Promise<ContextualReadResult<ProgramSummary>> {
    await abortableYield(signal);
    const value = this.engine.openProgram();
    return this.result(context, value);
  }

  public async listing(
    context: ViewContext,
    start: number,
    count: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<ListingPage>> {
    await abortableYield(signal);
    return this.result(context, this.engine.listing(start, count));
  }

  public async symbols(
    context: ViewContext,
    query: string,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly SymbolMatch[]>> {
    await abortableYield(signal);
    return this.result(context, this.engine.searchSymbols(query, limit));
  }

  public async decompile(
    context: ViewContext,
    row: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<DecompileResult>> {
    await abortableYield(signal);
    return this.result(context, this.engine.decompile(row));
  }

  public async references(
    context: ViewContext,
    row: number,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly ReferenceMatch[]>> {
    await abortableYield(signal);
    return this.result(context, this.engine.referencesTo(row).slice(0, limit));
  }

  public async evidence(
    context: ViewContext,
    row: number,
    limit: number,
    signal: AbortSignal,
  ): Promise<ContextualReadResult<readonly EvidenceRecord[]>> {
    await abortableYield(signal);
    return this.result(context, this.engine.evidenceFor(row).slice(0, limit));
  }

  private result<T>(requested: ViewContext, value: T): ContextualReadResult<T> {
    const summary = this.engine.openProgram();
    return {
      context: {
        runtimeId: requested.runtimeId,
        runtimeEpoch: requested.runtimeEpoch,
        programId: summary.id,
        contentGeneration: summary.revision,
      },
      resultId: `synthetic-read-${++this.resultSequence}`,
      value,
      completeness: 'COMPLETE',
      warnings: [],
    };
  }
}
