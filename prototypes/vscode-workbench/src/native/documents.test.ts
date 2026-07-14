import { describe, expect, it, vi } from 'vitest';

vi.mock('vscode', () => {
  class EventEmitter<T> {
    private listeners: ((value: T) => void)[] = [];
    public readonly event = (listener: (value: T) => void) => {
      this.listeners.push(listener);
      return { dispose: (): void => { this.listeners = this.listeners.filter(candidate => candidate !== listener); } };
    };
    public fire(value: T): void {
      for (const listener of this.listeners) listener(value);
    }
    public dispose(): void {
      this.listeners = [];
    }
  }

  class MockUri {
    public constructor(
      public readonly scheme: string,
      public readonly authority: string,
      public readonly path: string,
      public readonly query: string,
    ) {}
    public toString(): string {
      return `${this.scheme}://${this.authority}${this.path}?${this.query}`;
    }
  }

  class CancellationError extends Error {}

  return {
    EventEmitter,
    Uri: {
      from: ({ scheme, authority, path, query }: {
        scheme: string;
        authority: string;
        path: string;
        query: string;
      }) => new MockUri(scheme, authority, path, query),
    },
    FileSystemError: {
      FileNotFound: (uri: MockUri) => new Error(`File not found: ${uri.toString()}`),
    },
    CancellationError,
    SemanticTokensLegend: class SemanticTokensLegend {
      public constructor(
        public readonly tokenTypes: readonly string[],
        public readonly tokenModifiers: readonly string[],
      ) {}
    },
    SymbolKind: { Function: 11, Interface: 10, Variable: 12 },
  };
});

import type * as vscode from 'vscode';
import { TOTAL_ROWS } from '../core/engine';
import { SyntheticEngine } from '../core/engine';
import { NativeReadService, SyntheticReadTransport } from '../core/readService';
import {
  LISTING_WINDOW_ROWS,
  ListingDocumentProvider,
  SYNTHETIC_VIEW_CONTEXT,
  listingEditorLineForGlobalRow,
  listingGlobalRowForEditorLine,
  makeListingUri,
  parseNativeDocumentUri,
} from './documents';

const ACTIVE_TOKEN = { isCancellationRequested: false } as vscode.CancellationToken;

function readService(): NativeReadService {
  const engine = new SyntheticEngine();
  return new NativeReadService(new SyntheticReadTransport(engine), SYNTHETIC_VIEW_CONTEXT);
}

describe('bounded native listing documents', () => {
  it('projects at most 4096 rows and preserves an explicit line-to-semantic-row map', async () => {
    const provider = new ListingDocumentProvider(readService());
    const uri = makeListingUri(TOTAL_ROWS - 1);
    const content = await provider.provideTextDocumentContent(uri, ACTIVE_TOKEN);
    const projection = provider.projection(uri);
    const descriptor = parseNativeDocumentUri(uri);

    expect(descriptor).toMatchObject({ kind: 'listing', startRow: 98_304, rowCount: 1_696 });
    expect(content.split('\n')).toHaveLength(1_696);
    expect(projection?.rowCount).toBeLessThanOrEqual(LISTING_WINDOW_ROWS);
    expect(projection?.lines[0]).toMatchObject({
      editorLine: 0,
      globalRow: 98_304,
      semanticRow: {
        kind: 'INSTRUCTION',
        location: { requestedAddress: { spaceId: 'ram' }, byteOffset: 0 },
      },
    });
    expect(projection?.lines.at(-1)?.globalRow).toBe(TOTAL_ROWS - 1);
    expect(provider.editorLineForGlobalRow(uri, TOTAL_ROWS - 1)).toBe(1_695);
    expect(provider.lineRef(uri, 1_695)?.globalRow).toBe(TOTAL_ROWS - 1);
    provider.dispose();
  });

  it('uses stable shard URIs and translates without equating global rows to editor lines', () => {
    const row = 8_193;
    const uri = makeListingUri(row);
    expect(parseNativeDocumentUri(uri)).toMatchObject({ kind: 'listing', startRow: 8_192, rowCount: 4_096 });
    expect(listingEditorLineForGlobalRow(uri, row)).toBe(1);
    expect(listingGlobalRowForEditorLine(uri, 1)).toBe(row);
    expect(makeListingUri(8_194).toString()).toBe(uri.toString());
    expect(makeListingUri(12_288).toString()).not.toBe(uri.toString());
  });

  it('rejects a generation-mismatched document identity', async () => {
    const provider = new ListingDocumentProvider(readService());
    const uri = makeListingUri(0, { ...SYNTHETIC_VIEW_CONTEXT, contentGeneration: 16 });
    await expect(provider.provideTextDocumentContent(uri, ACTIVE_TOKEN)).rejects.toThrow(/File not found/);
    expect(provider.cachedProjectionCount).toBe(0);
    provider.dispose();
  });

  it('does not publish a projection when disposed before asynchronous completion', async () => {
    const provider = new ListingDocumentProvider(readService());
    const uri = makeListingUri(0);
    const pending = provider.provideTextDocumentContent(uri, ACTIVE_TOKEN);
    provider.dispose();

    await expect(pending).rejects.toThrow();
    expect(provider.cachedProjectionCount).toBe(0);
  });
});
