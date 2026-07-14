import { describe, expect, it } from 'vitest';
import {
  ViewStateReducer,
  createListingWindow,
  isOffcut,
  sameLocation,
  type AddressRef,
  type ListingRow,
  type ListingWindow,
  type LocationRef,
  type ResultCompleteness,
  type ViewContext,
  type ViewRequest,
} from './viewState';

const GENERATION_17: ViewContext = {
  runtimeId: 'runtime-local',
  runtimeEpoch: 3,
  programId: 'orbit-controller',
  contentGeneration: 17,
};

function address(spaceId: string, offsetBits: string, display: string): AddressRef {
  return { spaceId, spaceEpoch: 1, offsetBits, display };
}

const RAM_1000 = address('ram', '1000', 'ram:00001000');
const RAM_1001 = address('ram', '1001', 'ram:00001001');
const INSTRUCTION: LocationRef = {
  requestedAddress: RAM_1000,
  containingAddress: RAM_1000,
  byteOffset: 0,
  fieldId: 'mnemonic',
};
const OFFCUT: LocationRef = {
  requestedAddress: RAM_1001,
  containingAddress: RAM_1000,
  byteOffset: 1,
  fieldId: 'bytes',
};

function row(rowId: string, kind: ListingRow['kind'], location: LocationRef): ListingRow {
  return { rowId, kind, location, fields: { address: location.requestedAddress.display, mnemonic: 'mov' } };
}

function reducer(): ViewStateReducer {
  const result = new ViewStateReducer(GENERATION_17);
  result.registerView('listing', true);
  result.navigate(INSTRUCTION);
  return result;
}

function windowFor(request: ViewRequest, completeness: ResultCompleteness = 'COMPLETE'): ListingWindow {
  return createListingWindow({
    viewId: request.viewId,
    requestId: request.requestId,
    context: request.context,
    resultId: `result-${request.requestId}`,
    anchor: request.anchor,
    rows: [row('instruction', 'INSTRUCTION', request.anchor)],
    completeness,
    warnings: [],
  });
}

describe('semantic view-state spine', () => {
  it('SPINE-01 accepts only the latest request when responses reverse', () => {
    const state = reducer();
    const a = state.beginRequest('listing', INSTRUCTION, 512);
    const b = state.beginRequest('listing', INSTRUCTION, 512);
    const c = state.beginRequest('listing', INSTRUCTION, 512);

    expect(state.complete(windowFor(b))).toBe('IGNORED_SUPERSEDED');
    expect(state.complete(windowFor(a))).toBe('IGNORED_SUPERSEDED');
    expect(state.complete(windowFor(c))).toBe('ACCEPTED');
    expect(state.view('listing')).toMatchObject({ freshness: 'CURRENT', displayed: { requestId: c.requestId } });
  });

  it('SPINE-02 rejects an old generation and retains the coherent stale result', () => {
    const state = reducer();
    const original = state.beginRequest('listing', INSTRUCTION, 512);
    expect(state.complete(windowFor(original))).toBe('ACCEPTED');
    expect(state.acceptEvent(1, 18)).toBe('APPLIED');
    const refresh = state.beginRequest('listing', INSTRUCTION, 512);
    const stale = { ...windowFor(refresh), context: GENERATION_17, resultId: 'stale-generation' };

    expect(state.complete(stale)).toBe('IGNORED_CONTEXT');
    expect(state.view('listing')).toMatchObject({
      freshness: 'STALE',
      displayed: { resultId: `result-${original.requestId}` },
    });
    expect(state.view('listing')?.pending).toBeUndefined();
    expect(state.complete(windowFor(refresh))).toBe('IGNORED_SUPERSEDED');
  });

  it('SPINE-03/04 retains a coherent result across a gap and atomically resynchronizes', () => {
    const state = reducer();
    const original = state.beginRequest('listing', INSTRUCTION, 512);
    state.complete(windowFor(original));
    state.acceptEvent(41, 17);

    expect(state.acceptEvent(43, 18)).toBe('GAP_REQUIRES_RESYNC');
    expect(state.authoritativeActionsAllowed).toBe(false);
    expect(state.view('listing')).toMatchObject({
      freshness: 'RESYNCING',
      displayed: { resultId: `result-${original.requestId}` },
    });
    expect(state.acceptEvent(42, 18)).toBe('QUARANTINED_REQUIRES_SNAPSHOT');
    expect(() => state.beginRequest('listing', INSTRUCTION, 512)).toThrow(/full snapshot/);

    const generation18 = { ...GENERATION_17, contentGeneration: 18 };
    state.applySnapshot(generation18, 44);
    expect(state.snapshot()).toMatchObject({ context: generation18, eventSequence: 44, resyncRequired: false });
    const refresh = state.beginRequest('listing', INSTRUCTION, 512);
    expect(state.complete(windowFor(refresh))).toBe('ACCEPTED');
    expect(state.view('listing')).toMatchObject({ freshness: 'CURRENT', displayed: { context: generation18 } });
  });

  it('SPINE-05/06 preserves overlay identity and requested offcut identity', () => {
    const overlay = address('overlay:text', '1000', 'overlay:text:00001000');
    const overlayLocation: LocationRef = {
      requestedAddress: overlay,
      containingAddress: overlay,
      byteOffset: 0,
      fieldId: 'mnemonic',
    };

    expect(overlayLocation).not.toEqual(INSTRUCTION);
    expect(sameLocation(
      INSTRUCTION,
      {
        ...INSTRUCTION,
        requestedAddress: { ...RAM_1000, display: '0x1000' },
        containingAddress: { ...RAM_1000, display: 'memory:1000' },
      },
    )).toBe(true);
    expect(sameLocation(
      INSTRUCTION,
      {
        ...INSTRUCTION,
        requestedAddress: { ...RAM_1000, offsetBits: '00001000' },
        containingAddress: { ...RAM_1000, offsetBits: '00001000' },
      },
    )).toBe(true);
    expect(isOffcut(OFFCUT)).toBe(true);
    expect(OFFCUT).toMatchObject({ requestedAddress: RAM_1001, containingAddress: RAM_1000, byteOffset: 1 });
  });

  it('SPINE-07/08 retains row kinds and labels partial coverage', () => {
    const state = reducer();
    const request = state.beginRequest('listing', OFFCUT, 512);
    const partial = createListingWindow({
      viewId: 'listing',
      requestId: request.requestId,
      context: request.context,
      resultId: 'partial-result',
      anchor: request.anchor,
      rows: [
        row('insn', 'INSTRUCTION', INSTRUCTION),
        row('data', 'DATA', OFFCUT),
        row('gap', 'GAP', OFFCUT),
      ],
      completeness: 'PARTIAL',
      warnings: ['coverage ends at unmapped overlay'],
    });

    expect(state.complete(partial)).toBe('ACCEPTED');
    expect(state.view('listing')).toMatchObject({ freshness: 'PARTIAL', detail: 'coverage ends at unmapped overlay' });
    expect(state.view('listing')?.displayed?.rows.map(item => item.kind)).toEqual(['INSTRUCTION', 'DATA', 'GAP']);
  });

  it('SPINE-09 keeps location, selection, and owner highlights independent', () => {
    const state = reducer();
    state.setSelection(new Set([INSTRUCTION]));
    state.setHighlight('user', new Set([OFFCUT]));
    state.setHighlight('agent', new Set([INSTRUCTION]));
    state.navigate(OFFCUT);

    const snapshot = state.snapshot();
    expect(snapshot.location).toEqual(OFFCUT);
    expect([...snapshot.selection]).toEqual([INSTRUCTION]);
    expect([...(snapshot.highlights.get('user') ?? [])]).toEqual([OFFCUT]);
    expect([...(snapshot.highlights.get('agent') ?? [])]).toEqual([INSTRUCTION]);
  });

  it('SPINE-10 hides a disposed view, ignores its completion, and permits re-registration', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 512);
    state.disposeView('listing');

    expect(state.complete(windowFor(request))).toBe('IGNORED_DISPOSED_VIEW');
    expect(state.disposed).toBe(false);
    expect(state.view('listing')).toBeUndefined();
    expect(() => state.beginRequest('listing', INSTRUCTION, 512)).toThrow(/disposed/);

    state.registerView('listing', true);
    const replacement = state.beginRequest('listing', INSTRUCTION, 512);
    expect(state.complete(windowFor(request))).toBe('IGNORED_SUPERSEDED');
    expect(state.complete(windowFor(replacement))).toBe('ACCEPTED');
  });

  it('ignores all completions after workbench disposal', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 512);
    state.dispose();

    expect(state.complete(windowFor(request))).toBe('IGNORED_DISPOSED');
    expect(state.disposed).toBe(true);
  });

  it('SPINE-11 rejects request-limit overflow and duplicate semantic row IDs', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 1);
    const oversized = createListingWindow({
      ...windowFor(request),
      resultId: 'oversized',
      rows: [row('one', 'INSTRUCTION', INSTRUCTION), row('two', 'DATA', OFFCUT)],
    });
    expect(state.complete(oversized)).toBe('IGNORED_LIMIT');
    expect(state.view('listing')?.freshness).toBe('FAILED');
    expect(state.view('listing')?.pending).toBeUndefined();
    expect(state.complete(windowFor(request))).toBe('IGNORED_SUPERSEDED');

    expect(() => createListingWindow({
      ...windowFor(request),
      resultId: 'duplicates',
      rows: [row('same', 'INSTRUCTION', INSTRUCTION), row('same', 'DATA', OFFCUT)],
    })).toThrow(/unique/);
  });

  it('makes a current anchor mismatch terminal instead of leaving poisoned work pending', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 8);
    expect(state.complete({
      ...windowFor(request),
      resultId: 'wrong-anchor',
      anchor: OFFCUT,
    })).toBe('IGNORED_ANCHOR');
    expect(state.view('listing')?.freshness).toBe('FAILED');
    expect(state.view('listing')?.pending).toBeUndefined();
    expect(state.complete(windowFor(request))).toBe('IGNORED_SUPERSEDED');
  });

  it('rejects malformed transport enums and field payloads at runtime', () => {
    const request = reducer().beginRequest('listing', INSTRUCTION, 1);
    expect(() => createListingWindow({
      ...windowFor(request),
      completeness: 'UNKNOWN' as ResultCompleteness,
    })).toThrow(/completeness/);
    expect(() => createListingWindow({
      ...windowFor(request),
      rows: [{ ...row('bad', 'INSTRUCTION', INSTRUCTION), fields: { mnemonic: 7 } as unknown as Record<string, string> }],
    })).toThrow(/fields/);
    expect(() => createListingWindow({
      ...windowFor(request),
      rows: [{ ...row('bad-kind', 'INSTRUCTION', INSTRUCTION), kind: 'MYSTERY' as ListingRow['kind'] }],
    })).toThrow(/kind/);
  });

  it('copies provider-owned semantic records before retaining them', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 1);
    const mutableFields: Record<string, string> = { mnemonic: 'mov' };
    const mutableAddress = { ...RAM_1000 };
    const providerResult: ListingWindow = {
      ...windowFor(request),
      resultId: 'provider-owned',
      anchor: { ...INSTRUCTION, requestedAddress: mutableAddress },
      rows: [{
        rowId: 'provider-row',
        kind: 'INSTRUCTION',
        location: { ...INSTRUCTION, requestedAddress: mutableAddress },
        fields: mutableFields,
      }],
    };
    expect(state.complete(providerResult)).toBe('ACCEPTED');

    mutableAddress.offsetBits = 'deadbeef';
    mutableFields.mnemonic = 'corrupted';
    expect(state.view('listing')?.displayed).toMatchObject({
      anchor: { requestedAddress: { offsetBits: '1000' } },
      rows: [{ fields: { mnemonic: 'mov' } }],
    });
  });

  it('SPINE-12 generation regression requires resync and disables authority', () => {
    const state = reducer();
    state.acceptEvent(41, 17);
    expect(state.acceptEvent(42, 16)).toBe('INCONSISTENT_REQUIRES_RESYNC');
    expect(state.view('listing')?.freshness).toBe('RESYNCING');
    expect(state.authoritativeActionsAllowed).toBe(false);
  });

  it('quarantines completions and incrementals until a full snapshot resolves a gap', () => {
    const state = reducer();
    const pending = state.beginRequest('listing', INSTRUCTION, 8);
    state.acceptEvent(41, 17);
    expect(state.acceptEvent(43, 18)).toBe('GAP_REQUIRES_RESYNC');

    expect(state.complete(windowFor(pending))).toBe('IGNORED_RESYNC_REQUIRED');
    expect(state.fail('listing', pending.requestId)).toBe('IGNORED_RESYNC_REQUIRED');
    expect(state.acceptEvent(42, 18)).toBe('QUARANTINED_REQUIRES_SNAPSHOT');
    expect(state.view('listing')?.freshness).toBe('RESYNCING');
  });

  it('treats a same-sequence event with different content as inconsistent', () => {
    const state = reducer();
    expect(state.acceptEvent(41, 17)).toBe('APPLIED');
    expect(state.acceptEvent(41, 18)).toBe('INCONSISTENT_REQUIRES_RESYNC');
    expect(state.acceptEvent(42, 18)).toBe('QUARANTINED_REQUIRES_SNAPSHOT');
  });

  it('reserves sequence zero for full snapshots, not incrementals', () => {
    const state = reducer();
    expect(() => state.acceptEvent(0, 17)).toThrow(/positive/);
    expect(() => state.applySnapshot(GENERATION_17, 0)).not.toThrow();
  });

  it('does not let a full snapshot regress an incarnation epoch, generation, or sequence', () => {
    const state = reducer();
    state.acceptEvent(41, 17);

    expect(() => state.applySnapshot({ ...GENERATION_17, runtimeEpoch: 2 }, 0)).toThrow(/epoch/);
    expect(() => state.applySnapshot({ ...GENERATION_17, contentGeneration: 16 }, 42)).toThrow(/generation/);
    expect(() => state.applySnapshot({ ...GENERATION_17, programId: 'other-program', contentGeneration: 0 }, 0))
      .toThrow(/sequence/);
  });

  it('rejects offcuts that cross address-space identity', () => {
    const overlay = address('overlay:text', '1001', 'overlay:text:00001001');
    expect(() => createListingWindow({
      viewId: 'listing',
      requestId: 1,
      context: GENERATION_17,
      resultId: 'cross-space-offcut',
      anchor: {
        requestedAddress: overlay,
        containingAddress: RAM_1000,
        byteOffset: 1,
        fieldId: 'bytes',
      },
      rows: [],
      completeness: 'COMPLETE',
      warnings: [],
    })).toThrow(/same address space epoch/);
  });

  it('keeps covered navigation current and cancels pending work when navigation leaves the window', () => {
    const state = reducer();
    const ram1004 = address('ram', '1004', 'ram:00001004');
    const ram1008 = address('ram', '1008', 'ram:00001008');
    const second: LocationRef = {
      requestedAddress: ram1004,
      containingAddress: ram1004,
      byteOffset: 0,
      fieldId: 'mnemonic',
    };
    const outside: LocationRef = {
      requestedAddress: ram1008,
      containingAddress: ram1008,
      byteOffset: 0,
      fieldId: 'mnemonic',
    };
    const request = state.beginRequest('listing', INSTRUCTION, 8);
    expect(state.complete(createListingWindow({
      ...windowFor(request),
      resultId: 'two-rows',
      rows: [row('first', 'INSTRUCTION', INSTRUCTION), row('second', 'INSTRUCTION', second)],
    }))).toBe('ACCEPTED');

    state.navigate(second);
    expect(state.view('listing')?.freshness).toBe('CURRENT');
    const refresh = state.beginRequest('listing', second, 8);
    state.navigate(outside);
    expect(state.view('listing')?.freshness).toBe('STALE');
    expect(state.view('listing')?.pending).toBeUndefined();
    expect(state.complete(windowFor(refresh))).toBe('IGNORED_SUPERSEDED');
  });

  it('SPINE-13 runtime restart can reset sequence and clears runtime-local channels', () => {
    const state = reducer();
    const request = state.beginRequest('listing', INSTRUCTION, 8);
    state.complete(windowFor(request));
    state.setSelection(new Set([INSTRUCTION]));
    state.setHighlight('agent', new Set([OFFCUT]));
    state.acceptEvent(41, 17);
    const restarted = { ...GENERATION_17, runtimeEpoch: 4 };

    state.applySnapshot(restarted, 0);
    expect(state.snapshot()).toMatchObject({ context: restarted, eventSequence: 0 });
    expect(state.snapshot().location).toBeUndefined();
    expect(state.snapshot().selection.size).toBe(0);
    expect(state.snapshot().highlights.size).toBe(0);
    expect(state.view('listing')?.target).toBeUndefined();
    expect(state.view('listing')?.displayed).toBeUndefined();
  });

  it('SPINE-14 connected views registered after navigation inherit the current location', () => {
    const state = new ViewStateReducer(GENERATION_17);
    state.navigate(OFFCUT);
    state.registerView('listing', true);
    expect(state.view('listing')?.target).toEqual(OFFCUT);
  });
});
