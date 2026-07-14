export const MAX_LISTING_WINDOW_ROWS = 4_096;

export type ViewFreshness =
  | 'EMPTY'
  | 'LOADING'
  | 'CURRENT'
  | 'STALE'
  | 'PARTIAL'
  | 'FAILED'
  | 'RESYNCING';

export type ResultCompleteness = 'COMPLETE' | 'PARTIAL' | 'TRUNCATED';
export type ListingRowKind = 'INSTRUCTION' | 'DATA' | 'GAP';

export type ResultAcceptance =
  | 'ACCEPTED'
  | 'IGNORED_DISPOSED'
  | 'IGNORED_DISPOSED_VIEW'
  | 'IGNORED_UNKNOWN_VIEW'
  | 'IGNORED_SUPERSEDED'
  | 'IGNORED_CONTEXT'
  | 'IGNORED_ANCHOR'
  | 'IGNORED_LIMIT'
  | 'IGNORED_RESYNC_REQUIRED';

export type EventAcceptance =
  | 'APPLIED'
  | 'DUPLICATE_OR_OLD'
  | 'GAP_REQUIRES_RESYNC'
  | 'INCONSISTENT_REQUIRES_RESYNC'
  | 'QUARANTINED_REQUIRES_SNAPSHOT';

export interface ViewContext {
  readonly runtimeId: string;
  readonly runtimeEpoch: number;
  readonly programId: string;
  readonly contentGeneration: number;
}

export interface AddressRef {
  readonly spaceId: string;
  readonly spaceEpoch: number;
  readonly offsetBits: string;
  readonly display: string;
}

export interface LocationRef {
  readonly requestedAddress: AddressRef;
  readonly containingAddress: AddressRef;
  readonly byteOffset: number;
  readonly fieldId: string;
}

export interface ListingRow {
  readonly rowId: string;
  readonly kind: ListingRowKind;
  readonly location: LocationRef;
  readonly fields: Readonly<Record<string, string>>;
}

export interface ViewRequest {
  readonly viewId: string;
  readonly requestId: number;
  readonly context: ViewContext;
  readonly anchor: LocationRef;
  readonly limit: number;
}

export interface ListingWindow {
  readonly viewId: string;
  readonly requestId: number;
  readonly context: ViewContext;
  readonly resultId: string;
  readonly anchor: LocationRef;
  readonly rows: readonly ListingRow[];
  readonly completeness: ResultCompleteness;
  readonly warnings: readonly string[];
}

export interface ViewSnapshot {
  readonly viewId: string;
  readonly connected: boolean;
  readonly freshness: ViewFreshness;
  readonly target?: LocationRef;
  readonly pending?: ViewRequest;
  readonly displayed?: ListingWindow;
  readonly detail: string;
}

export interface WorkbenchSnapshot {
  readonly context: ViewContext;
  readonly eventSequence: number;
  readonly resyncRequired: boolean;
  readonly location?: LocationRef;
  readonly selection: ReadonlySet<LocationRef>;
  readonly highlights: ReadonlyMap<string, ReadonlySet<LocationRef>>;
  readonly views: ReadonlyMap<string, ViewSnapshot>;
}

interface MutableView {
  readonly id: string;
  readonly connected: boolean;
  disposed: boolean;
  freshness: ViewFreshness;
  target?: LocationRef;
  pending?: ViewRequest;
  displayed?: ListingWindow;
  detail: string;
}

function requireText(value: string, name: string): void {
  if (value.trim().length === 0) throw new TypeError(`${name} must not be blank`);
}

function requireNonNegativeInteger(value: number, name: string): void {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new RangeError(`${name} must be a non-negative safe integer`);
  }
}

function validateContext(context: ViewContext): void {
  requireText(context.runtimeId, 'runtimeId');
  requireText(context.programId, 'programId');
  requireNonNegativeInteger(context.runtimeEpoch, 'runtimeEpoch');
  requireNonNegativeInteger(context.contentGeneration, 'contentGeneration');
}

function validateAddress(address: AddressRef): void {
  requireText(address.spaceId, 'spaceId');
  requireText(address.display, 'display');
  requireNonNegativeInteger(address.spaceEpoch, 'spaceEpoch');
  if (!/^[0-9a-f]+$/i.test(address.offsetBits)) {
    throw new TypeError('offsetBits must be an opaque hexadecimal bit pattern');
  }
}

function canonicalOffsetBits(offsetBits: string): string {
  return offsetBits.toLowerCase().replace(/^0+(?=[0-9a-f])/, '');
}

function validateLocation(location: LocationRef): void {
  validateAddress(location.requestedAddress);
  validateAddress(location.containingAddress);
  requireText(location.fieldId, 'fieldId');
  requireNonNegativeInteger(location.byteOffset, 'byteOffset');
  if (location.requestedAddress.spaceId !== location.containingAddress.spaceId ||
      location.requestedAddress.spaceEpoch !== location.containingAddress.spaceEpoch) {
    throw new TypeError('Requested and containing addresses must belong to the same address space epoch');
  }
  if (location.byteOffset === 0 && !sameAddress(location.requestedAddress, location.containingAddress)) {
    throw new TypeError('A non-identical requested address requires an offcut byte offset');
  }
}

function sameAddress(left: AddressRef, right: AddressRef): boolean {
  return left.spaceId === right.spaceId &&
    left.spaceEpoch === right.spaceEpoch &&
    canonicalOffsetBits(left.offsetBits) === canonicalOffsetBits(right.offsetBits);
}

export function sameLocation(left: LocationRef, right: LocationRef): boolean {
  return sameAddress(left.requestedAddress, right.requestedAddress) &&
    sameAddress(left.containingAddress, right.containingAddress) &&
    left.byteOffset === right.byteOffset &&
    left.fieldId === right.fieldId;
}

export function isOffcut(location: LocationRef): boolean {
  return location.byteOffset !== 0 || !sameAddress(location.requestedAddress, location.containingAddress);
}

export function sameViewContext(left: ViewContext, right: ViewContext): boolean {
  return left.runtimeId === right.runtimeId &&
    left.runtimeEpoch === right.runtimeEpoch &&
    left.programId === right.programId &&
    left.contentGeneration === right.contentGeneration;
}

function validateListingWindow(result: ListingWindow): void {
  requireText(result.viewId, 'viewId');
  requireText(result.resultId, 'resultId');
  validateContext(result.context);
  validateLocation(result.anchor);
  if (!Number.isSafeInteger(result.requestId) || result.requestId <= 0) {
    throw new RangeError('requestId must be a positive safe integer');
  }
  if (result.rows.length > MAX_LISTING_WINDOW_ROWS) {
    throw new RangeError(`A listing projection may contain at most ${MAX_LISTING_WINDOW_ROWS} rows`);
  }
  if (result.completeness !== 'COMPLETE' && result.completeness !== 'PARTIAL' &&
      result.completeness !== 'TRUNCATED') {
    throw new TypeError('Unknown result completeness');
  }
  if (!Array.isArray(result.warnings) || result.warnings.some(warning => typeof warning !== 'string')) {
    throw new TypeError('warnings must contain only strings');
  }
  const rowIds = new Set<string>();
  for (const row of result.rows) {
    requireText(row.rowId, 'rowId');
    if (row.kind !== 'INSTRUCTION' && row.kind !== 'DATA' && row.kind !== 'GAP') {
      throw new TypeError('Unknown semantic row kind');
    }
    validateLocation(row.location);
    if (typeof row.fields !== 'object' || row.fields === null || Array.isArray(row.fields) ||
        Object.entries(row.fields).some(([key, value]) => key.length === 0 || typeof value !== 'string')) {
      throw new TypeError('row fields must be a string-to-string record');
    }
    if (rowIds.has(row.rowId)) throw new TypeError('Semantic row IDs must be unique within a window');
    rowIds.add(row.rowId);
  }
}

function copyAddress(address: AddressRef): AddressRef {
  return {
    spaceId: address.spaceId,
    spaceEpoch: address.spaceEpoch,
    offsetBits: canonicalOffsetBits(address.offsetBits),
    display: address.display,
  };
}

function copyLocation(location: LocationRef): LocationRef {
  return {
    requestedAddress: copyAddress(location.requestedAddress),
    containingAddress: copyAddress(location.containingAddress),
    byteOffset: location.byteOffset,
    fieldId: location.fieldId,
  };
}

function copyRequest(request: ViewRequest): ViewRequest {
  return {
    viewId: request.viewId,
    requestId: request.requestId,
    context: { ...request.context },
    anchor: copyLocation(request.anchor),
    limit: request.limit,
  };
}

/** Validates and copies a provider result at the host-neutral boundary. */
export function createListingWindow(result: ListingWindow): ListingWindow {
  validateListingWindow(result);
  return {
    ...result,
    context: { ...result.context },
    anchor: copyLocation(result.anchor),
    rows: result.rows.map(row => ({
      rowId: row.rowId,
      kind: row.kind,
      location: copyLocation(row.location),
      fields: { ...row.fields },
    })),
    warnings: [...result.warnings],
  };
}

/**
 * Host-neutral reducer for bounded semantic views.
 *
 * It deliberately owns no VS Code, transport, timer, filesystem, or Ghidra objects. A host starts
 * asynchronous work from a ViewRequest and offers the immutable result here before updating UI.
 */
export class ViewStateReducer {
  private readonly views = new Map<string, MutableView>();
  private readonly highlightState = new Map<string, ReadonlySet<LocationRef>>();
  private selectionState: ReadonlySet<LocationRef> = new Set();
  private locationState: LocationRef | undefined;
  private nextRequestId = 0;
  private eventSequenceState = 0;
  private lastEventContentGeneration: number;
  private resyncRequiredState = false;
  private disposedState = false;

  public constructor(private contextState: ViewContext) {
    validateContext(contextState);
    this.contextState = { ...contextState };
    this.lastEventContentGeneration = contextState.contentGeneration;
  }

  public registerView(viewId: string, connected: boolean): void {
    this.ensureOpen();
    requireText(viewId, 'viewId');
    const existing = this.views.get(viewId);
    if (existing !== undefined && !existing.disposed) throw new TypeError(`View already registered: ${viewId}`);
    this.views.set(viewId, {
      id: viewId,
      connected,
      disposed: false,
      freshness: 'EMPTY',
      ...(connected && this.locationState !== undefined ? { target: this.locationState } : {}),
      detail: '',
    });
  }

  public navigate(location: LocationRef): void {
    this.ensureOpen();
    validateLocation(location);
    const safeLocation = copyLocation(location);
    this.locationState = safeLocation;
    for (const view of this.views.values()) {
      if (!view.connected || view.disposed) continue;
      view.target = safeLocation;
      if (view.pending !== undefined && !sameLocation(view.pending.anchor, safeLocation)) {
        view.pending = undefined;
      }
      if (view.displayed === undefined) {
        if (view.pending === undefined && view.freshness === 'LOADING') {
          view.freshness = this.resyncRequiredState ? 'RESYNCING' : 'EMPTY';
          view.detail = 'Connected location changed before the window completed';
        }
        continue;
      }
      const currentWindowCoversLocation = (view.freshness === 'CURRENT' || view.freshness === 'PARTIAL') &&
        windowCovers(view.displayed, safeLocation);
      if (!currentWindowCoversLocation) {
        view.freshness = this.resyncRequiredState ? 'RESYNCING' : 'STALE';
        view.detail = 'Displayed result does not cover the connected location';
      }
    }
  }

  public setSelection(selection: ReadonlySet<LocationRef> | undefined): void {
    this.ensureOpen();
    this.selectionState = new Set([...(selection ?? [])].map(copyLocation));
  }

  public setHighlight(owner: string, locations: ReadonlySet<LocationRef> | undefined): void {
    this.ensureOpen();
    requireText(owner, 'owner');
    const values = new Set([...(locations ?? [])].map(copyLocation));
    if (values.size === 0) this.highlightState.delete(owner);
    else this.highlightState.set(owner, values);
  }

  public beginRequest(viewId: string, anchor: LocationRef, limit: number): ViewRequest {
    this.ensureOpen();
    if (this.resyncRequiredState) {
      throw new Error('A validated full snapshot is required before requesting views');
    }
    const view = this.requireActiveView(viewId);
    validateLocation(anchor);
    if (!Number.isSafeInteger(limit) || limit <= 0 || limit > MAX_LISTING_WINDOW_ROWS) {
      throw new RangeError(`limit must be between 1 and ${MAX_LISTING_WINDOW_ROWS}`);
    }
    const request: ViewRequest = {
      viewId,
      requestId: ++this.nextRequestId,
      context: { ...this.contextState },
      anchor: copyLocation(anchor),
      limit,
    };
    view.target = request.anchor;
    view.pending = request;
    view.freshness = view.displayed === undefined ? 'LOADING' : 'STALE';
    view.detail = view.displayed === undefined ? 'Loading bounded listing window' : 'Refreshing stale window';
    return request;
  }

  public complete(untrustedResult: ListingWindow): ResultAcceptance {
    if (this.disposedState) return 'IGNORED_DISPOSED';
    const view = this.views.get(untrustedResult.viewId);
    if (view === undefined) return 'IGNORED_UNKNOWN_VIEW';
    if (view.disposed) return 'IGNORED_DISPOSED_VIEW';
    if (this.resyncRequiredState) return 'IGNORED_RESYNC_REQUIRED';
    validateListingWindow(untrustedResult);
    const pending = view.pending;
    if (pending === undefined || pending.requestId !== untrustedResult.requestId) return 'IGNORED_SUPERSEDED';
    if (!sameViewContext(pending.context, untrustedResult.context) ||
        !sameViewContext(this.contextState, untrustedResult.context)) {
      return this.rejectCurrent(
        view,
        'IGNORED_CONTEXT',
        'Provider returned a mismatched runtime/program/generation context',
      );
    }
    if (!sameLocation(pending.anchor, untrustedResult.anchor)) {
      return this.rejectCurrent(
        view,
        'IGNORED_ANCHOR',
        'Provider returned a window for a different semantic anchor',
      );
    }
    if (untrustedResult.rows.length > pending.limit) {
      return this.rejectCurrent(
        view,
        'IGNORED_LIMIT',
        'Provider exceeded the requested bounded row limit',
      );
    }

    const result = createListingWindow(untrustedResult);
    view.displayed = result;
    view.pending = undefined;
    view.freshness = result.completeness === 'COMPLETE' ? 'CURRENT' : 'PARTIAL';
    view.detail = result.warnings.length === 0
      ? `${result.rows.length} semantic rows`
      : result.warnings.join(' · ');
    return 'ACCEPTED';
  }

  public fail(viewId: string, requestId: number, detail = 'Listing request failed'): ResultAcceptance {
    if (this.disposedState) return 'IGNORED_DISPOSED';
    const view = this.views.get(viewId);
    if (view === undefined) return 'IGNORED_UNKNOWN_VIEW';
    if (view.disposed) return 'IGNORED_DISPOSED_VIEW';
    if (this.resyncRequiredState) return 'IGNORED_RESYNC_REQUIRED';
    if (view.pending === undefined || view.pending.requestId !== requestId) return 'IGNORED_SUPERSEDED';
    view.pending = undefined;
    view.freshness = view.displayed === undefined ? 'FAILED' : 'STALE';
    view.detail = detail;
    return 'ACCEPTED';
  }

  public acceptEvent(sequence: number, contentGeneration: number): EventAcceptance {
    this.ensureOpen();
    requireNonNegativeInteger(sequence, 'sequence');
    requireNonNegativeInteger(contentGeneration, 'contentGeneration');
    if (sequence === 0) throw new RangeError('Incremental event sequence must be a positive safe integer');
    if (this.resyncRequiredState) return 'QUARANTINED_REQUIRES_SNAPSHOT';
    if (sequence < this.eventSequenceState) return 'DUPLICATE_OR_OLD';
    if (sequence === this.eventSequenceState) {
      if (contentGeneration === this.lastEventContentGeneration) return 'DUPLICATE_OR_OLD';
      this.resyncRequiredState = true;
      this.markViews('RESYNCING', 'Conflicting duplicate event; full snapshot required', true);
      return 'INCONSISTENT_REQUIRES_RESYNC';
    }
    if (this.eventSequenceState !== 0 && sequence !== this.eventSequenceState + 1) {
      this.resyncRequiredState = true;
      this.markViews('RESYNCING', 'Event sequence gap; full snapshot required', true);
      return 'GAP_REQUIRES_RESYNC';
    }
    if (contentGeneration < this.contextState.contentGeneration) {
      this.resyncRequiredState = true;
      this.markViews('RESYNCING', 'Content generation moved backward; full snapshot required', true);
      return 'INCONSISTENT_REQUIRES_RESYNC';
    }
    this.eventSequenceState = sequence;
    this.lastEventContentGeneration = contentGeneration;
    if (contentGeneration > this.contextState.contentGeneration) {
      this.contextState = { ...this.contextState, contentGeneration };
      this.markViews('STALE', 'Program content generation advanced', true);
    }
    return 'APPLIED';
  }

  public applySnapshot(context: ViewContext, sequence: number): void {
    this.ensureOpen();
    validateContext(context);
    requireNonNegativeInteger(sequence, 'sequence');
    const sameRuntimeId = this.contextState.runtimeId === context.runtimeId;
    if (sameRuntimeId && context.runtimeEpoch < this.contextState.runtimeEpoch) {
      throw new RangeError('A runtime epoch cannot move backward');
    }
    const sameRuntimeIncarnation = sameRuntimeId &&
      this.contextState.runtimeEpoch === context.runtimeEpoch;
    const sameProgram = this.contextState.programId === context.programId;
    if (sameRuntimeIncarnation && sequence < this.eventSequenceState) {
      throw new RangeError('A full snapshot cannot move event sequence backward');
    }
    if (sameRuntimeIncarnation && sameProgram &&
        context.contentGeneration < this.contextState.contentGeneration) {
      throw new RangeError('A content generation cannot move backward');
    }
    const identityChanged = !sameRuntimeIncarnation || !sameProgram;
    this.contextState = { ...context };
    this.eventSequenceState = sequence;
    this.lastEventContentGeneration = context.contentGeneration;
    this.resyncRequiredState = false;
    if (identityChanged) {
      this.locationState = undefined;
      this.selectionState = new Set();
      this.highlightState.clear();
    }
    for (const view of this.views.values()) {
      if (view.disposed) continue;
      view.pending = undefined;
      if (identityChanged) {
        view.target = undefined;
        view.displayed = undefined;
        view.freshness = 'EMPTY';
        view.detail = 'Validated snapshot changed runtime or program identity';
      } else if (view.displayed !== undefined) {
        view.freshness = 'STALE';
        view.detail = 'Validated snapshot replaced the runtime context';
      } else {
        view.freshness = 'EMPTY';
        view.detail = 'Validated snapshot applied';
      }
    }
  }

  public view(viewId: string): ViewSnapshot | undefined {
    const view = this.views.get(viewId);
    return view === undefined || view.disposed ? undefined : this.copyView(view);
  }

  public snapshot(): WorkbenchSnapshot {
    const highlights = new Map<string, ReadonlySet<LocationRef>>();
    for (const [owner, values] of this.highlightState) {
      highlights.set(owner, new Set([...values].map(copyLocation)));
    }
    const views = new Map<string, ViewSnapshot>();
    for (const [id, view] of this.views) {
      if (!view.disposed) views.set(id, this.copyView(view));
    }
    return {
      context: { ...this.contextState },
      eventSequence: this.eventSequenceState,
      resyncRequired: this.resyncRequiredState,
      ...(this.locationState === undefined ? {} : { location: copyLocation(this.locationState) }),
      selection: new Set([...this.selectionState].map(copyLocation)),
      highlights,
      views,
    };
  }

  public get disposed(): boolean {
    return this.disposedState;
  }

  public get authoritativeActionsAllowed(): boolean {
    return !this.disposedState && !this.resyncRequiredState;
  }

  public dispose(): void {
    if (this.disposedState) return;
    this.disposedState = true;
    for (const view of this.views.values()) {
      view.pending = undefined;
      view.target = undefined;
      view.displayed = undefined;
      view.disposed = true;
    }
  }

  public disposeView(viewId: string): void {
    if (this.disposedState) return;
    const view = this.views.get(viewId);
    if (view === undefined || view.disposed) return;
    view.pending = undefined;
    view.target = undefined;
    view.displayed = undefined;
    view.disposed = true;
    view.freshness = 'EMPTY';
    view.detail = 'View disposed';
  }

  private markViews(freshness: ViewFreshness, detail: string, clearPending: boolean): void {
    for (const view of this.views.values()) {
      if (view.disposed) continue;
      if (clearPending) view.pending = undefined;
      view.freshness = view.displayed === undefined && freshness === 'STALE' ? 'EMPTY' : freshness;
      view.detail = detail;
    }
  }

  private requireActiveView(viewId: string): MutableView {
    const view = this.views.get(viewId);
    if (view === undefined) throw new TypeError(`Unknown view: ${viewId}`);
    if (view.disposed) throw new Error(`View is disposed: ${viewId}`);
    return view;
  }

  private rejectCurrent(
    view: MutableView,
    acceptance: ResultAcceptance,
    detail: string,
  ): ResultAcceptance {
    view.pending = undefined;
    view.freshness = view.displayed === undefined ? 'FAILED' : 'STALE';
    view.detail = detail;
    return acceptance;
  }

  private copyView(view: MutableView): ViewSnapshot {
    return {
      viewId: view.id,
      connected: view.connected,
      freshness: view.freshness,
      ...(view.target === undefined ? {} : { target: copyLocation(view.target) }),
      ...(view.pending === undefined ? {} : { pending: copyRequest(view.pending) }),
      ...(view.displayed === undefined ? {} : { displayed: createListingWindow(view.displayed) }),
      detail: view.detail,
    };
  }

  private ensureOpen(): void {
    if (this.disposedState) throw new Error('View-state reducer is disposed');
  }
}

function windowCovers(window: ListingWindow, location: LocationRef): boolean {
  if (sameLocation(window.anchor, location)) return true;
  return window.rows.some(row => {
    const candidate = row.location;
    return sameAddress(candidate.requestedAddress, location.requestedAddress) ||
      sameAddress(candidate.containingAddress, location.containingAddress) ||
      sameAddress(candidate.containingAddress, location.requestedAddress) ||
      sameAddress(candidate.requestedAddress, location.containingAddress);
  });
}
