import type {
  AnalysisState,
  DecompileFunctionResult,
  DecompilerView,
  EngineLimits,
  ListingItem,
  ListingRow,
  ListingWindow,
  ListingWindowResult,
  ProgramSummaryResult,
  ProtocolContext,
  ProtocolErrorCode,
  ProtocolEvent,
  ProtocolFailureResponse,
  ProtocolRequest,
  ReferenceItem,
  ReferencesListResult,
  ReferenceView,
  SessionHelloResult,
  SessionReadyData,
  SymbolItem,
  SymbolRecord,
  SymbolsSearchResult,
  WorkbenchEngineMode,
  WorkbenchEventHandlers,
  WorkbenchSnapshot,
  WorkbenchSubscription,
} from "./types";

const LEGACY_API_ROOT = "/api/v1";
const ENGINE_REQUESTS_PATH = "/engine/v1/requests";
const ENGINE_EVENTS_PATH = "/engine/v1/events";
const PROTOCOL_VERSION = 1;
const CLIENT_VERSION = "0.1.0";
const DEFAULT_TIMEOUT_MS = 5_000;
const MAX_EVENT_LINE_BYTES = 2_097_152;

const PROGRAM_METHODS = new Set([
  "program.summary",
  "listing.window",
  "symbols.search",
  "decompile.function",
  "references.list",
]);

const ERROR_CODES = new Set<ProtocolErrorCode>([
  "INVALID_REQUEST",
  "UNSUPPORTED_VERSION",
  "AUTH_FAILED",
  "METHOD_NOT_FOUND",
  "INVALID_PARAMS",
  "SESSION_NOT_READY",
  "PROGRAM_MISMATCH",
  "REVISION_MISMATCH",
  "NOT_FOUND",
  "LIMIT_EXCEEDED",
  "DEADLINE_EXCEEDED",
  "REQUEST_CANCELLED",
  "ENGINE_BUSY",
  "ENGINE_UNAVAILABLE",
  "GHIDRA_FAILURE",
  "INTERNAL",
]);

type Fetch = typeof fetch;

export interface WorkbenchApi {
  readonly mode: WorkbenchEngineMode;
  snapshot(): Promise<WorkbenchSnapshot>;
  searchSymbols(query: string): Promise<{ query: string; symbols: SymbolRecord[] }>;
  listing(address: string, rows?: number): Promise<ListingWindow>;
  decompile(address: string): Promise<DecompilerView>;
  references(address: string): Promise<ReferenceView>;
  startAnalysis(): Promise<AnalysisState>;
  cancelAnalysis(): Promise<AnalysisState>;
  supports(method: string): boolean;
  subscribe(handlers: WorkbenchEventHandlers): WorkbenchSubscription;
}

export class EngineProtocolError extends Error {
  readonly code: ProtocolErrorCode;
  readonly retryable: boolean;

  constructor(error: ProtocolFailureResponse["error"]) {
    super(error.message);
    this.name = "EngineProtocolError";
    this.code = error.code;
    this.retryable = error.retryable;
  }
}

class ContractError extends Error {
  constructor(path: string, message: string) {
    super(`Protocol violation at ${path}: ${message}`);
    this.name = "ContractError";
  }
}

function record(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new ContractError(path, "expected an object");
  }
  return value as Record<string, unknown>;
}

function exactObject(
  value: unknown,
  path: string,
  required: readonly string[],
  optional: readonly string[] = [],
): Record<string, unknown> {
  const result = record(value, path);
  const allowed = new Set([...required, ...optional]);
  for (const key of Object.keys(result)) {
    if (!allowed.has(key)) throw new ContractError(`${path}.${key}`, "unknown field");
  }
  for (const key of required) {
    if (!(key in result)) throw new ContractError(`${path}.${key}`, "missing field");
  }
  return result;
}

function stringValue(value: unknown, path: string, min = 1, max = 2_048): string {
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new ContractError(path, `expected a string with length ${min}..${max}`);
  }
  return value;
}

function booleanValue(value: unknown, path: string): boolean {
  if (typeof value !== "boolean") throw new ContractError(path, "expected a boolean");
  return value;
}

function integerValue(value: unknown, path: string, min = 0, max = Number.MAX_SAFE_INTEGER): number {
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) {
    throw new ContractError(path, `expected an integer in ${min}..${max}`);
  }
  return value as number;
}

function arrayValue(value: unknown, path: string, max: number): unknown[] {
  if (!Array.isArray(value) || value.length > max) {
    throw new ContractError(path, `expected an array with at most ${max} items`);
  }
  return value;
}

function literal<T extends string | number | boolean>(value: unknown, expected: T, path: string): T {
  if (value !== expected) throw new ContractError(path, `expected ${String(expected)}`);
  return expected;
}

function optionalString(value: unknown, path: string, max: number): string | undefined {
  return value === undefined ? undefined : stringValue(value, path, 1, max);
}

function contextValue(value: unknown, path: string): ProtocolContext {
  const source = exactObject(value, path, ["programId", "revision"]);
  return {
    programId: stringValue(source.programId, `${path}.programId`, 1, 128),
    revision: integerValue(source.revision, `${path}.revision`),
  };
}

function identityValue(value: unknown, path: string): { name: string; version: string; instanceId?: string } {
  const source = exactObject(value, path, ["name", "version"], ["instanceId"]);
  const instanceId = optionalString(source.instanceId, `${path}.instanceId`, 128);
  return {
    name: stringValue(source.name, `${path}.name`, 1, 128),
    version: stringValue(source.version, `${path}.version`, 1, 128),
    ...(instanceId === undefined ? {} : { instanceId }),
  };
}

function sameContext(left: ProtocolContext, right: ProtocolContext): boolean {
  return left.programId === right.programId && left.revision === right.revision;
}

function parseError(value: unknown): ProtocolFailureResponse["error"] {
  const source = exactObject(value, "response.error", ["code", "message", "retryable"], ["retryAfterMs", "details"]);
  const code = stringValue(source.code, "response.error.code", 1, 64) as ProtocolErrorCode;
  if (!ERROR_CODES.has(code)) throw new ContractError("response.error.code", "unknown error code");
  const retryAfterMs = source.retryAfterMs === undefined
    ? undefined
    : integerValue(source.retryAfterMs, "response.error.retryAfterMs", 0, 30_000);
  const details = source.details === undefined ? undefined : record(source.details, "response.error.details");
  if (details && Object.keys(details).length > 32) throw new ContractError("response.error.details", "too many fields");
  return {
    code,
    message: stringValue(source.message, "response.error.message", 1, 2_048),
    retryable: booleanValue(source.retryable, "response.error.retryable"),
    ...(retryAfterMs === undefined ? {} : { retryAfterMs }),
    ...(details === undefined ? {} : { details }),
  };
}

function readyDataValue(value: unknown): SessionReadyData {
  const source = exactObject(value, "event.data", ["sessionId", "protocol", "server", "ghidra", "context", "programSha256"]);
  const range = exactObject(source.protocol, "event.data.protocol", ["min", "max"]);
  const ghidra = exactObject(source.ghidra, "event.data.ghidra", ["version"]);
  const digest = stringValue(source.programSha256, "event.data.programSha256", 64, 64);
  if (!/^[0-9a-f]{64}$/.test(digest)) throw new ContractError("event.data.programSha256", "expected lowercase SHA-256");
  return {
    sessionId: stringValue(source.sessionId, "event.data.sessionId", 1, 128),
    protocol: {
      min: integerValue(range.min, "event.data.protocol.min", 1),
      max: integerValue(range.max, "event.data.protocol.max", 1),
    },
    server: identityValue(source.server, "event.data.server"),
    ghidra: {
      version: stringValue(ghidra.version, "event.data.ghidra.version", 1, 128),
    },
    context: contextValue(source.context, "event.data.context"),
    programSha256: digest,
  };
}

function eventValue(value: unknown): ProtocolEvent {
  const source = exactObject(value, "event", ["kind", "version", "event", "sequence", "data"], ["context"]);
  literal(source.kind, "event", "event.kind");
  literal(source.version, PROTOCOL_VERSION, "event.version");
  const event = stringValue(source.event, "event.event", 3, 128);
  if (!/^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+$/.test(event)) {
    throw new ContractError("event.event", "invalid event name");
  }
  const context = source.context === undefined ? undefined : contextValue(source.context, "event.context");
  return {
    kind: "event",
    version: 1,
    event,
    sequence: integerValue(source.sequence, "event.sequence", 1),
    ...(context === undefined ? {} : { context }),
    data: record(source.data, "event.data"),
  };
}

function helloResultValue(value: unknown): SessionHelloResult {
  const source = exactObject(value, "response.result", ["selectedVersion", "sessionId", "server", "ghidra", "capabilities"]);
  const ghidra = exactObject(source.ghidra, "response.result.ghidra", ["version"]);
  const capabilities = exactObject(source.capabilities, "response.result.capabilities", ["methods", "events", "transports", "limits"]);
  const limitsSource = exactObject(
    capabilities.limits,
    "response.result.capabilities.limits",
    ["maxFrameBytes", "maxTimeoutMs", "maxListingItems", "maxSymbolItems", "maxReferenceItems", "maxDecompileChars"],
  );
  const methods = arrayValue(capabilities.methods, "response.result.capabilities.methods", 128)
    .map((item, index) => stringValue(item, `response.result.capabilities.methods[${index}]`, 3, 128));
  const events = arrayValue(capabilities.events, "response.result.capabilities.events", 128)
    .map((item, index) => stringValue(item, `response.result.capabilities.events[${index}]`, 3, 128));
  const transports = arrayValue(capabilities.transports, "response.result.capabilities.transports", 2)
    .map((item, index) => {
      const transport = stringValue(item, `response.result.capabilities.transports[${index}]`, 1, 32);
      if (transport !== "stdio" && transport !== "loopback-http") {
        throw new ContractError(`response.result.capabilities.transports[${index}]`, "unknown transport");
      }
      return transport;
    });
  if (new Set(methods).size !== methods.length || new Set(events).size !== events.length || new Set(transports).size !== transports.length) {
    throw new ContractError("response.result.capabilities", "capability arrays must be unique");
  }
  const limits: EngineLimits = {
    maxFrameBytes: integerValue(limitsSource.maxFrameBytes, "response.result.capabilities.limits.maxFrameBytes", 65_536, 2_097_152),
    maxTimeoutMs: integerValue(limitsSource.maxTimeoutMs, "response.result.capabilities.limits.maxTimeoutMs", 100, 30_000),
    maxListingItems: integerValue(limitsSource.maxListingItems, "response.result.capabilities.limits.maxListingItems", 1, 1_000),
    maxSymbolItems: integerValue(limitsSource.maxSymbolItems, "response.result.capabilities.limits.maxSymbolItems", 1, 500),
    maxReferenceItems: integerValue(limitsSource.maxReferenceItems, "response.result.capabilities.limits.maxReferenceItems", 1, 2_000),
    maxDecompileChars: integerValue(limitsSource.maxDecompileChars, "response.result.capabilities.limits.maxDecompileChars", 1_024, 500_000),
  };
  return {
    selectedVersion: literal(source.selectedVersion, 1, "response.result.selectedVersion"),
    sessionId: stringValue(source.sessionId, "response.result.sessionId", 1, 128),
    server: identityValue(source.server, "response.result.server"),
    ghidra: { version: stringValue(ghidra.version, "response.result.ghidra.version", 1, 128) },
    capabilities: { methods, events, transports, limits },
  };
}

function summaryResultValue(value: unknown): ProgramSummaryResult {
  const source = exactObject(
    value,
    "response.result",
    ["name", "executableFormat", "languageId", "compilerSpecId", "imageBase", "minAddress", "maxAddress", "sha256", "analysis", "memoryBlockCount", "instructionCount", "functionCount", "symbolCount"],
  );
  const analysis = exactObject(source.analysis, "response.result.analysis", ["complete", "timedOut"]);
  const sha256 = stringValue(source.sha256, "response.result.sha256", 64, 64);
  if (!/^[0-9a-f]{64}$/.test(sha256)) throw new ContractError("response.result.sha256", "expected lowercase SHA-256");
  return {
    name: stringValue(source.name, "response.result.name", 1, 512),
    executableFormat: stringValue(source.executableFormat, "response.result.executableFormat", 1, 256),
    languageId: stringValue(source.languageId, "response.result.languageId", 1, 256),
    compilerSpecId: stringValue(source.compilerSpecId, "response.result.compilerSpecId", 1, 256),
    imageBase: stringValue(source.imageBase, "response.result.imageBase", 1, 128),
    minAddress: stringValue(source.minAddress, "response.result.minAddress", 1, 128),
    maxAddress: stringValue(source.maxAddress, "response.result.maxAddress", 1, 128),
    sha256,
    analysis: {
      complete: booleanValue(analysis.complete, "response.result.analysis.complete"),
      timedOut: booleanValue(analysis.timedOut, "response.result.analysis.timedOut"),
    },
    memoryBlockCount: integerValue(source.memoryBlockCount, "response.result.memoryBlockCount"),
    instructionCount: integerValue(source.instructionCount, "response.result.instructionCount"),
    functionCount: integerValue(source.functionCount, "response.result.functionCount"),
    symbolCount: integerValue(source.symbolCount, "response.result.symbolCount"),
  };
}

function listingItemValue(value: unknown, index: number): ListingItem {
  const path = `response.result.items[${index}]`;
  const source = exactObject(value, path, ["address", "length", "kind"], ["label", "mnemonic", "operands", "bytes", "flowType", "function"]);
  const kind = stringValue(source.kind, `${path}.kind`, 1, 32);
  if (kind !== "instruction" && kind !== "data" && kind !== "undefined") throw new ContractError(`${path}.kind`, "unknown listing kind");
  const bytes = optionalString(source.bytes, `${path}.bytes`, 2_097_152);
  if (bytes !== undefined && !/^([0-9a-f]{2})*$/.test(bytes)) throw new ContractError(`${path}.bytes`, "expected lowercase hexadecimal byte pairs");
  return {
    address: stringValue(source.address, `${path}.address`, 1, 128),
    length: integerValue(source.length, `${path}.length`, 1, 1_048_576),
    kind,
    ...(optionalString(source.label, `${path}.label`, 1_024) === undefined ? {} : { label: optionalString(source.label, `${path}.label`, 1_024) }),
    ...(optionalString(source.mnemonic, `${path}.mnemonic`, 256) === undefined ? {} : { mnemonic: optionalString(source.mnemonic, `${path}.mnemonic`, 256) }),
    ...(source.operands === undefined ? {} : { operands: stringValue(source.operands, `${path}.operands`, 0, 4_096) }),
    ...(bytes === undefined ? {} : { bytes }),
    ...(optionalString(source.flowType, `${path}.flowType`, 128) === undefined ? {} : { flowType: optionalString(source.flowType, `${path}.flowType`, 128) }),
    ...(optionalString(source.function, `${path}.function`, 1_024) === undefined ? {} : { function: optionalString(source.function, `${path}.function`, 1_024) }),
  };
}

function listingResultValue(value: unknown): ListingWindowResult {
  const source = exactObject(value, "response.result", ["items", "truncated"], ["nextCursor", "previousCursor"]);
  const items = arrayValue(source.items, "response.result.items", 1_000).map(listingItemValue);
  const nextCursor = optionalString(source.nextCursor, "response.result.nextCursor", 1_024);
  const previousCursor = optionalString(source.previousCursor, "response.result.previousCursor", 1_024);
  return {
    items,
    truncated: booleanValue(source.truncated, "response.result.truncated"),
    ...(nextCursor === undefined ? {} : { nextCursor }),
    ...(previousCursor === undefined ? {} : { previousCursor }),
  };
}

const SYMBOL_KINDS = new Set<SymbolItem["kind"]>(["label", "function", "namespace", "class", "parameter", "local", "external", "library", "unknown"]);

function symbolItemValue(value: unknown, index: number): SymbolItem {
  const path = `response.result.items[${index}]`;
  const source = exactObject(value, path, ["name", "address", "kind", "primary"], ["namespace", "source"]);
  const kind = stringValue(source.kind, `${path}.kind`, 1, 32) as SymbolItem["kind"];
  if (!SYMBOL_KINDS.has(kind)) throw new ContractError(`${path}.kind`, "unknown symbol kind");
  const namespace = optionalString(source.namespace, `${path}.namespace`, 2_048);
  const provenance = optionalString(source.source, `${path}.source`, 128);
  return {
    name: stringValue(source.name, `${path}.name`, 1, 1_024),
    address: stringValue(source.address, `${path}.address`, 1, 128),
    kind,
    primary: booleanValue(source.primary, `${path}.primary`),
    ...(namespace === undefined ? {} : { namespace }),
    ...(provenance === undefined ? {} : { source: provenance }),
  };
}

function symbolsResultValue(value: unknown): SymbolsSearchResult {
  const source = exactObject(value, "response.result", ["items", "truncated"], ["nextCursor"]);
  const nextCursor = optionalString(source.nextCursor, "response.result.nextCursor", 1_024);
  return {
    items: arrayValue(source.items, "response.result.items", 500).map(symbolItemValue),
    truncated: booleanValue(source.truncated, "response.result.truncated"),
    ...(nextCursor === undefined ? {} : { nextCursor }),
  };
}

function decompileResultValue(value: unknown): DecompileFunctionResult {
  const source = exactObject(value, "response.result", ["function", "language", "text", "truncated", "warnings"]);
  const fn = exactObject(source.function, "response.result.function", ["name", "entryPoint", "signature"]);
  return {
    function: {
      name: stringValue(fn.name, "response.result.function.name", 1, 1_024),
      entryPoint: stringValue(fn.entryPoint, "response.result.function.entryPoint", 1, 128),
      signature: stringValue(fn.signature, "response.result.function.signature", 1, 16_384),
    },
    language: literal(source.language, "c", "response.result.language"),
    text: stringValue(source.text, "response.result.text", 0, 500_000),
    truncated: booleanValue(source.truncated, "response.result.truncated"),
    warnings: arrayValue(source.warnings, "response.result.warnings", 64)
      .map((warning, index) => stringValue(warning, `response.result.warnings[${index}]`, 1, 2_048)),
  };
}

function referenceItemValue(value: unknown, index: number): ReferenceItem {
  const path = `response.result.items[${index}]`;
  const source = exactObject(value, path, ["fromAddress", "toAddress", "type", "primary", "external"], ["operandIndex"]);
  const operandIndex = source.operandIndex === undefined ? undefined : integerValue(source.operandIndex, `${path}.operandIndex`, -1, 65_535);
  return {
    fromAddress: stringValue(source.fromAddress, `${path}.fromAddress`, 1, 128),
    toAddress: stringValue(source.toAddress, `${path}.toAddress`, 1, 128),
    type: stringValue(source.type, `${path}.type`, 1, 128),
    primary: booleanValue(source.primary, `${path}.primary`),
    external: booleanValue(source.external, `${path}.external`),
    ...(operandIndex === undefined ? {} : { operandIndex }),
  };
}

function referencesResultValue(value: unknown): ReferencesListResult {
  const source = exactObject(value, "response.result", ["items", "truncated"], ["nextCursor"]);
  const nextCursor = optionalString(source.nextCursor, "response.result.nextCursor", 1_024);
  return {
    items: arrayValue(source.items, "response.result.items", 2_000).map(referenceItemValue),
    truncated: booleanValue(source.truncated, "response.result.truncated"),
    ...(nextCursor === undefined ? {} : { nextCursor }),
  };
}

const RESULT_VALIDATORS: Record<string, (value: unknown) => Record<string, unknown>> = {
  "session.hello": helloResultValue,
  "program.summary": summaryResultValue,
  "listing.window": listingResultValue,
  "symbols.search": symbolsResultValue,
  "decompile.function": decompileResultValue,
  "references.list": referencesResultValue,
};

async function jsonResponse(response: Response, operation: string): Promise<unknown> {
  if (response.redirected || response.type === "opaqueredirect") throw new Error(`${operation} refused a redirect`);
  if (!response.ok) throw new Error(`${operation} returned HTTP ${response.status}`);
  const contentType = response.headers.get("content-type")?.toLocaleLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) throw new Error(`${operation} returned an unexpected content type`);
  try {
    return await response.json();
  } catch {
    throw new Error(`${operation} returned invalid JSON`);
  }
}

function parseNdjsonLine(line: string): ProtocolEvent {
  if (new TextEncoder().encode(line).length > MAX_EVENT_LINE_BYTES) throw new ContractError("event", "frame exceeds the protocol limit");
  let decoded: unknown;
  try {
    decoded = JSON.parse(line) as unknown;
  } catch {
    throw new ContractError("event", "invalid JSON");
  }
  return eventValue(decoded);
}

async function eventResponse(fetcher: Fetch, after: number, signal: AbortSignal): Promise<Response> {
  const response = await fetcher(ENGINE_EVENTS_PATH, {
    method: "GET",
    headers: {
      Accept: "application/x-ndjson",
      "X-GhidraEx-After-Sequence": String(after),
    },
    cache: "no-store",
    credentials: "same-origin",
    redirect: "error",
    signal,
  });
  if (response.redirected || response.type === "opaqueredirect") throw new Error("Engine event stream refused a redirect");
  if (!response.ok) throw new Error(`Engine event stream returned HTTP ${response.status}`);
  const contentType = response.headers.get("content-type")?.toLocaleLowerCase() ?? "";
  if (!contentType.startsWith("application/x-ndjson")) throw new Error("Engine event stream returned an unexpected content type");
  return response;
}

async function* ndjsonEvents(response: Response): AsyncGenerator<ProtocolEvent> {
  if (!response.body) throw new Error("Engine event stream has no response body");
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let pending = "";
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      pending += decoder.decode(chunk.value, { stream: true });
      if (new TextEncoder().encode(pending).length > MAX_EVENT_LINE_BYTES && !pending.includes("\n")) {
        throw new ContractError("event", "frame exceeds the protocol limit");
      }
      let newline = pending.indexOf("\n");
      while (newline >= 0) {
        const line = pending.slice(0, newline).replace(/\r$/, "");
        pending = pending.slice(newline + 1);
        if (line.length > 0) yield parseNdjsonLine(line);
        newline = pending.indexOf("\n");
      }
    }
    pending += decoder.decode();
    const finalLine = pending.replace(/\r$/, "");
    if (finalLine.length > 0) yield parseNdjsonLine(finalLine);
  } finally {
    await reader.cancel().catch(() => undefined);
  }
}

function legacyRequest<T>(fetcher: Fetch, path: string, init?: RequestInit): Promise<T> {
  return fetcher(`${LEGACY_API_ROOT}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
    redirect: "error",
  }).then(async (response) => {
    if (!response.ok) throw new Error(`Workbench service returned ${response.status}`);
    return response.json() as Promise<T>;
  });
}

export function createLegacyWorkbenchApi(
  fetcher: Fetch = fetch,
  eventSourceFactory: (url: string) => EventSource = (url) => new EventSource(url),
): WorkbenchApi {
  return {
    mode: "synthetic",
    snapshot: () => legacyRequest<WorkbenchSnapshot>(fetcher, "/snapshot"),
    searchSymbols: (query) => legacyRequest<{ query: string; symbols: SymbolRecord[] }>(fetcher, `/symbols?q=${encodeURIComponent(query)}`),
    listing: (address, rows = 50) => legacyRequest<ListingWindow>(fetcher, `/listing?address=${encodeURIComponent(address)}&rows=${rows}`),
    decompile: async () => { throw new Error("The synthetic service has no location decompiler endpoint"); },
    references: async () => { throw new Error("The synthetic service has no location references endpoint"); },
    startAnalysis: () => legacyRequest<AnalysisState>(fetcher, "/analysis/start", { method: "POST" }),
    cancelAnalysis: () => legacyRequest<AnalysisState>(fetcher, "/analysis/cancel", { method: "POST" }),
    supports: (method) => method === "analysis.start" || method === "analysis.cancel",
    subscribe: (handlers) => {
      const events = eventSourceFactory(`${LEGACY_API_ROOT}/events`);
      events.addEventListener("analysis", (event) => {
        try {
          handlers.analysis?.(JSON.parse((event as MessageEvent<string>).data) as AnalysisState);
        } catch {
          handlers.stale?.(new Error("Legacy analysis event was malformed"));
        }
      });
      events.addEventListener("engine", () => handlers.engine?.());
      events.onerror = () => handlers.stale?.(new Error("Legacy event stream disconnected"));
      return { close: () => events.close() };
    },
  };
}

export class RealGatewayWorkbenchApi implements WorkbenchApi {
  readonly mode = "real" as const;
  private readonly fetcher: Fetch;
  private context: ProtocolContext | null = null;
  private ready: SessionReadyData | null = null;
  private hello: SessionHelloResult | null = null;
  private negotiation: Promise<void> | null = null;
  private requestSequence = 0;
  private lastEventSequence = 0;
  private functionCount = 0;

  constructor(fetcher?: Fetch) {
    // Window.fetch performs a Web IDL receiver check in browsers.  Keeping the
    // raw function as an object property would invoke it with this API instance
    // as the receiver and fail before protocol negotiation.
    this.fetcher = fetcher ?? globalThis.fetch.bind(globalThis);
  }

  supports(method: string): boolean {
    return this.hello?.capabilities.methods.includes(method) ?? false;
  }

  async snapshot(): Promise<WorkbenchSnapshot> {
    await this.ensureNegotiated();
    this.requireCapability("program.summary");
    this.requireCapability("listing.window");
    this.requireCapability("symbols.search");

    const summary = await this.call("program.summary", {}, summaryResultValue);
    if (this.ready && summary.sha256 !== this.ready.programSha256) {
      throw new ContractError("response.result.sha256", "program identity changed after session.ready");
    }
    this.functionCount = summary.functionCount;
    const listingLimit = Math.min(50, this.hello!.capabilities.limits.maxListingItems);
    const symbolLimit = Math.min(100, this.hello!.capabilities.limits.maxSymbolItems);
    const symbols = await this.call("symbols.search", { query: "", limit: symbolLimit }, symbolsResultValue);
    const firstFunction = symbols.items.find((symbol) => symbol.kind === "function");
    const initialAddress = firstFunction?.address ?? summary.imageBase;
    const listing = await this.call(
      "listing.window",
      { start: initialAddress, direction: "forward", limit: listingLimit, includeBytes: true },
      listingResultValue,
    );
    const rows = listing.items.map(mapListingItem);
    const location = rows[0]?.address ?? initialAddress;
    const [decompiler, references] = await Promise.all([
      this.supports("decompile.function") && firstFunction
        ? this.decompile(location)
        : Promise.resolve(unavailableDecompiler(location)),
      this.supports("references.list") ? this.references(location) : Promise.resolve(emptyReferences(location)),
    ]);

    return {
      apiVersion: 1,
      project: {
        name: summary.name,
        binary: summary.name,
        format: summary.executableFormat,
        architecture: `${summary.languageId} · ${summary.compilerSpecId}`,
        imageBase: summary.imageBase,
        sha256: summary.sha256,
      },
      backend: {
        mode: "ghidra-headless",
        health: "connected",
        version: this.hello!.ghidra.version,
        launcher: "Persistent Ghidra engine · protocol v1 · authenticated loopback gateway",
        capabilities: ["program.read"],
      },
      analysis: summaryAnalysis(summary),
      symbols: symbols.items.map(mapSymbolItem),
      listingInfo: {
        totalInstructions: summary.instructionCount,
        minAddress: summary.minAddress,
        maxAddress: summary.maxAddress,
        viewportRows: rows.length,
      },
      listing: rows,
      decompiler: decompiler.source,
      inspector: {
        address: location,
        functionName: decompiler.functionName,
        signature: decompiler.signature,
        segment: "Program memory",
        offset: "Unavailable from protocol v1",
        xrefsIn: references.incoming,
        xrefsOut: references.outgoing,
        stackDelta: 0,
        prototypeSource: "Ghidra decompiler · protocol v1",
      },
    };
  }

  async searchSymbols(query: string): Promise<{ query: string; symbols: SymbolRecord[] }> {
    await this.ensureNegotiated();
    this.requireCapability("symbols.search");
    if (query.length > 256) throw new Error("Symbol query is longer than the protocol limit");
    const limit = Math.min(100, this.hello!.capabilities.limits.maxSymbolItems);
    const result = await this.call("symbols.search", { query, limit }, symbolsResultValue);
    return { query, symbols: result.items.map(mapSymbolItem) };
  }

  async listing(address: string, rows = 50): Promise<ListingWindow> {
    await this.ensureNegotiated();
    this.requireCapability("listing.window");
    const opaqueAddress = validateOpaqueAddress(address);
    const limit = Math.min(validateRequestedLimit(rows, 1_000), this.hello!.capabilities.limits.maxListingItems);
    const result = await this.call(
      "listing.window",
      { start: opaqueAddress, direction: "forward", limit, includeBytes: true },
      listingResultValue,
    );
    const mapped = result.items.map(mapListingItem);
    return {
      apiVersion: 1,
      requestedAddress: opaqueAddress,
      totalInstructions: mapped.length,
      minAddress: mapped[0]?.address ?? opaqueAddress,
      maxAddress: mapped.at(-1)?.address ?? opaqueAddress,
      rows: mapped,
    };
  }

  async decompile(address: string): Promise<DecompilerView> {
    await this.ensureNegotiated();
    this.requireCapability("decompile.function");
    const opaqueAddress = validateOpaqueAddress(address);
    const maxChars = Math.min(200_000, this.hello!.capabilities.limits.maxDecompileChars);
    const result = await this.call("decompile.function", { address: opaqueAddress, maxChars }, decompileResultValue);
    return {
      address: result.function.entryPoint,
      functionName: result.function.name,
      signature: result.function.signature,
      source: result.text,
      truncated: result.truncated,
      warnings: [...result.warnings],
    };
  }

  async references(address: string): Promise<ReferenceView> {
    await this.ensureNegotiated();
    this.requireCapability("references.list");
    const opaqueAddress = validateOpaqueAddress(address);
    const limit = Math.min(256, this.hello!.capabilities.limits.maxReferenceItems);
    const result = await this.call("references.list", { address: opaqueAddress, direction: "both", limit }, referencesResultValue);
    return {
      address: opaqueAddress,
      incoming: result.items.filter((item) => item.toAddress === opaqueAddress).length,
      outgoing: result.items.filter((item) => item.fromAddress === opaqueAddress).length,
      items: result.items.map((item) => ({ ...item })),
      truncated: result.truncated,
    };
  }

  async startAnalysis(): Promise<AnalysisState> {
    throw new Error("The negotiated engine does not expose an analysis-start capability");
  }

  async cancelAnalysis(): Promise<AnalysisState> {
    throw new Error("The negotiated engine does not expose an analysis-cancel capability");
  }

  subscribe(handlers: WorkbenchEventHandlers): WorkbenchSubscription {
    const controller = new AbortController();
    void this.pumpEvents(handlers, controller.signal).catch((error: unknown) => {
      if (!controller.signal.aborted) handlers.stale?.(asError(error));
    });
    return { close: () => controller.abort() };
  }

  private async ensureNegotiated(): Promise<void> {
    if (this.hello !== null) return;
    this.negotiation ??= this.negotiate().catch((error: unknown) => {
      this.negotiation = null;
      throw error;
    });
    await this.negotiation;
  }

  private async negotiate(): Promise<void> {
    const controller = new AbortController();
    const readyDeadline = globalThis.setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS);
    try {
      const response = await eventResponse(this.fetcher, 0, controller.signal);
      const oldest = parseSequenceHeader(response.headers.get("x-ghidraex-oldest-sequence"), "oldest");
      if (oldest !== null && oldest > 1) throw new Error("Engine event history no longer contains session.ready");
      for await (const event of ndjsonEvents(response)) {
        if (event.event !== "session.ready") continue;
        const ready = readyDataValue(event.data);
        if (ready.protocol.min > PROTOCOL_VERSION || ready.protocol.max < PROTOCOL_VERSION) {
          throw new Error("Engine and browser have no compatible protocol version");
        }
        if (event.context !== undefined && !sameContext(event.context, ready.context)) {
          throw new ContractError("event.context", "does not match session.ready data.context");
        }
        this.ready = ready;
        this.context = ready.context;
        this.lastEventSequence = event.sequence;
        break;
      }
    } catch (error) {
      if (controller.signal.aborted) throw new Error("Engine did not replay session.ready before the bootstrap deadline");
      throw error;
    } finally {
      globalThis.clearTimeout(readyDeadline);
      controller.abort();
    }
    if (!this.ready || !this.context) throw new Error("Engine did not replay the required session.ready event");

    const hello = await this.call(
      "session.hello",
      {
        client: { name: "ghidraex-web-workbench", version: CLIENT_VERSION },
        protocol: { min: 1, max: 1 },
        requestedCapabilities: ["program.summary", "listing.window", "symbols.search", "decompile.function", "references.list"],
      },
      helloResultValue,
      false,
    );
    if (hello.sessionId !== this.ready.sessionId) throw new ContractError("response.result.sessionId", "does not match session.ready");
    if (hello.ghidra.version !== this.ready.ghidra.version) throw new ContractError("response.result.ghidra.version", "does not match session.ready");
    if (!hello.capabilities.transports.includes("loopback-http")) throw new ContractError("response.result.capabilities.transports", "gateway did not advertise loopback-http");
    this.hello = hello;
  }

  private async call<TResult extends Record<string, unknown>>(
    method: string,
    params: Record<string, unknown>,
    validator: (value: unknown) => TResult,
    withContext = PROGRAM_METHODS.has(method),
  ): Promise<TResult> {
    if (withContext && !this.context) throw new Error("Engine program context is unavailable");
    const id = `web-${Date.now().toString(36)}-${(++this.requestSequence).toString(36)}`;
    const request: ProtocolRequest = {
      kind: "request",
      version: 1,
      id,
      method,
      ...(withContext ? { context: this.context! } : {}),
      timeoutMs: DEFAULT_TIMEOUT_MS,
      params,
    };
    const controller = new AbortController();
    const transportDeadline = globalThis.setTimeout(() => controller.abort(), DEFAULT_TIMEOUT_MS + 2_000);
    let response: Response;
    try {
      response = await this.fetcher(ENGINE_REQUESTS_PATH, {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "application/json" },
        body: JSON.stringify(request),
        cache: "no-store",
        credentials: "same-origin",
        redirect: "error",
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) throw new Error(`Engine method ${method} exceeded its transport deadline`);
      throw error;
    } finally {
      globalThis.clearTimeout(transportDeadline);
    }
    const decoded = await jsonResponse(response, `Engine method ${method}`);
    const envelope = exactObject(decoded, "response", ["kind", "version", "id", "method", "ok"], ["context", "result", "error"]);
    literal(envelope.kind, "response", "response.kind");
    literal(envelope.version, PROTOCOL_VERSION, "response.version");
    literal(envelope.id, id, "response.id");
    literal(envelope.method, method, "response.method");
    const ok = booleanValue(envelope.ok, "response.ok");
    if (!ok) {
      if (envelope.result !== undefined || envelope.error === undefined || envelope.context !== undefined) {
        throw new ContractError("response", "invalid error response fields");
      }
      throw new EngineProtocolError(parseError(envelope.error));
    }
    if (envelope.error !== undefined || envelope.result === undefined) throw new ContractError("response", "invalid success response fields");
    if (withContext) {
      if (envelope.context === undefined) throw new ContractError("response.context", "program response did not echo context");
      const echoed = contextValue(envelope.context, "response.context");
      if (!sameContext(echoed, this.context!)) throw new ContractError("response.context", "does not match the active program view");
    } else if (envelope.context !== undefined) {
      throw new ContractError("response.context", "context is forbidden for this method");
    }
    const selectedValidator = RESULT_VALIDATORS[method];
    if (selectedValidator !== validator) throw new Error(`No canonical result validator is registered for ${method}`);
    return validator(envelope.result);
  }

  private requireCapability(method: string): void {
    if (!this.supports(method)) throw new Error(`Engine did not advertise ${method}`);
  }

  private async pumpEvents(handlers: WorkbenchEventHandlers, signal: AbortSignal): Promise<void> {
    await this.ensureNegotiated();
    while (!signal.aborted) {
      const response = await eventResponse(this.fetcher, this.lastEventSequence, signal);
      const oldest = parseSequenceHeader(response.headers.get("x-ghidraex-oldest-sequence"), "oldest");
      if (oldest !== null && oldest > this.lastEventSequence + 1) {
        throw new Error("Engine event history has a sequence gap; views are stale");
      }
      for await (const event of ndjsonEvents(response)) {
        if (event.sequence <= this.lastEventSequence) continue;
        if (event.sequence !== this.lastEventSequence + 1) throw new Error("Engine event stream skipped a sequence; views are stale");
        this.lastEventSequence = event.sequence;
        if (event.context !== undefined && this.context !== null && !sameContext(event.context, this.context)) {
          throw new Error("Engine event belongs to another program context");
        }
        if (event.event === "session.ready") {
          const ready = readyDataValue(event.data);
          if (!this.ready || ready.sessionId !== this.ready.sessionId || !sameContext(ready.context, this.context!)) {
            throw new Error("Engine session restarted; views are stale");
          }
        } else if (event.event === "analysis.progress") {
          handlers.analysis?.(analysisEventValue(event.data, this.functionCount));
        } else if (event.event === "engine.warning") {
          const warning = exactObject(event.data, "event.data", ["code", "message"]);
          handlers.warning?.(`${stringValue(warning.code, "event.data.code", 1, 128)}: ${stringValue(warning.message, "event.data.message", 1, 2_048)}`);
        } else if (event.event === "session.closing") {
          const closing = exactObject(event.data, "event.data", ["reason"], ["message"]);
          throw new Error(`Engine session is closing: ${stringValue(closing.reason, "event.data.reason", 1, 64)}`);
        }
        handlers.engine?.();
      }
    }
  }
}

function analysisEventValue(value: unknown, functionCount: number): AnalysisState {
  const source = exactObject(value, "event.data", ["phase", "completed", "total"], ["message"]);
  const phase = stringValue(source.phase, "event.data.phase", 1, 32);
  if (phase !== "import" && phase !== "analysis" && phase !== "finalizing") throw new ContractError("event.data.phase", "unknown analysis phase");
  const completed = integerValue(source.completed, "event.data.completed");
  const total = integerValue(source.total, "event.data.total");
  return {
    status: "running",
    progress: total === 0 ? 0 : Math.min(100, Math.round((completed / total) * 100)),
    phase: optionalString(source.message, "event.data.message", 512) ?? phase,
    elapsedMs: 0,
    functionsDiscovered: functionCount,
  };
}

function parseSequenceHeader(value: string | null, label: string): number | null {
  if (value === null) return null;
  if (!/^(0|[1-9][0-9]{0,15})$/.test(value)) throw new Error(`Engine event ${label} sequence header is invalid`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new Error(`Engine event ${label} sequence header is outside the safe range`);
  return parsed;
}

function mapListingItem(item: ListingItem): ListingRow {
  const flowType = item.flowType?.toLocaleLowerCase() ?? "";
  const flow: ListingRow["flow"] = flowType.includes("return") || flowType.includes("terminal")
    ? "return"
    : flowType.includes("call")
      ? "call"
      : flowType.includes("jump") || flowType.includes("branch")
        ? "branch"
        : "normal";
  return {
    address: item.address,
    bytes: item.bytes?.match(/.{1,2}/g)?.join(" ") ?? "",
    mnemonic: item.mnemonic ?? item.kind,
    operands: item.operands ?? "",
    annotation: item.label ?? item.function ?? "",
    flow,
  };
}

function mapSymbolItem(item: SymbolItem): SymbolRecord {
  return {
    name: item.name,
    address: item.address,
    namespace: item.namespace ?? "Global",
    kind: item.kind,
    refs: 0,
    confidence: 0,
  };
}

function summaryAnalysis(summary: ProgramSummaryResult): AnalysisState {
  return {
    status: summary.analysis.complete ? "complete" : summary.analysis.timedOut ? "cancelled" : "idle",
    progress: summary.analysis.complete ? 100 : 0,
    phase: summary.analysis.complete ? "Initial analysis complete" : summary.analysis.timedOut ? "Initial analysis timed out" : "Initial analysis incomplete",
    elapsedMs: 0,
    functionsDiscovered: summary.functionCount,
  };
}

function unavailableDecompiler(address: string): DecompilerView {
  return {
    address,
    functionName: "No decompiler capability",
    signature: "Decompiler unavailable in this engine session",
    source: "/* decompile.function was not advertised by the negotiated engine */",
    truncated: false,
    warnings: ["decompile.function is unavailable"],
  };
}

function emptyReferences(address: string): ReferenceView {
  return { address, incoming: 0, outgoing: 0, items: [], truncated: false };
}

function validateOpaqueAddress(value: string): string {
  if (typeof value !== "string" || value.length < 1 || value.length > 128 || value.trim() !== value) {
    throw new Error("Address must be a canonical engine string between 1 and 128 characters");
  }
  return value;
}

function validateRequestedLimit(value: number, maximum: number): number {
  if (!Number.isInteger(value) || value < 1 || value > maximum) throw new Error(`Row limit must be an integer between 1 and ${maximum}`);
  return value;
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error("Unknown engine event failure");
}

const configuredMode = import.meta.env.VITE_GHIDRAEX_ENGINE_MODE ?? "synthetic";
if (configuredMode !== "synthetic" && configuredMode !== "real") {
  throw new Error("VITE_GHIDRAEX_ENGINE_MODE must be either synthetic or real");
}

export const workbenchApi: WorkbenchApi = configuredMode === "real"
  ? new RealGatewayWorkbenchApi()
  : createLegacyWorkbenchApi();
