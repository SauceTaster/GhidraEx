/// <reference types="vite/client" />

export type AnalysisStatus = "idle" | "running" | "complete" | "cancelled";

export interface AnalysisState {
  status: AnalysisStatus;
  progress: number;
  phase: string;
  elapsedMs: number;
  functionsDiscovered: number;
}

export interface SymbolRecord {
  name: string;
  address: string;
  namespace: string;
  kind: string;
  refs: number;
  confidence: number;
}

export interface ListingRow {
  address: string;
  bytes: string;
  mnemonic: string;
  operands: string;
  annotation: string;
  flow: "normal" | "branch" | "call" | "return";
}

export interface ListingWindow {
  apiVersion: 1;
  requestedAddress: string;
  totalInstructions: number;
  minAddress: string;
  maxAddress: string;
  rows: ListingRow[];
}

export interface InspectorState {
  address: string;
  functionName: string;
  signature: string;
  segment: string;
  offset: string;
  xrefsIn: number;
  xrefsOut: number;
  stackDelta: number;
  prototypeSource: string;
}

export interface WorkbenchSnapshot {
  apiVersion: 1;
  project: {
    name: string;
    binary: string;
    format: string;
    architecture: string;
    imageBase: string;
    sha256: string;
  };
  backend: BackendState;
  analysis: AnalysisState;
  symbols: SymbolRecord[];
  listingInfo: {
    totalInstructions: number;
    minAddress: string;
    maxAddress: string;
    viewportRows: number;
  };
  listing: ListingRow[];
  decompiler: string;
  inspector: InspectorState;
}

/**
 * The browser never interprets an engine address. It only stores, compares, and
 * returns the canonical string supplied by Ghidra.
 */
export type EngineAddress = string;

export interface ProtocolContext {
  programId: string;
  revision: number;
}

export type ProtocolErrorCode =
  | "INVALID_REQUEST"
  | "UNSUPPORTED_VERSION"
  | "AUTH_FAILED"
  | "METHOD_NOT_FOUND"
  | "INVALID_PARAMS"
  | "SESSION_NOT_READY"
  | "PROGRAM_MISMATCH"
  | "REVISION_MISMATCH"
  | "NOT_FOUND"
  | "LIMIT_EXCEEDED"
  | "DEADLINE_EXCEEDED"
  | "REQUEST_CANCELLED"
  | "ENGINE_BUSY"
  | "ENGINE_UNAVAILABLE"
  | "GHIDRA_FAILURE"
  | "INTERNAL";

export interface ProtocolRequest<TParams extends Record<string, unknown> = Record<string, unknown>> {
  kind: "request";
  version: 1;
  id: string;
  method: string;
  context?: ProtocolContext;
  timeoutMs: number;
  params: TParams;
}

export interface ProtocolSuccessResponse<TResult extends Record<string, unknown> = Record<string, unknown>> {
  kind: "response";
  version: 1;
  id: string;
  method: string;
  ok: true;
  context?: ProtocolContext;
  result: TResult;
}

export interface ProtocolFailureResponse {
  kind: "response";
  version: 1;
  id: string;
  method: string;
  ok: false;
  error: {
    code: ProtocolErrorCode;
    message: string;
    retryable: boolean;
    retryAfterMs?: number;
    details?: Record<string, unknown>;
  };
}

export type ProtocolResponse<TResult extends Record<string, unknown> = Record<string, unknown>> =
  | ProtocolSuccessResponse<TResult>
  | ProtocolFailureResponse;

export interface ProtocolEvent<TData extends Record<string, unknown> = Record<string, unknown>> {
  kind: "event";
  version: 1;
  event: string;
  sequence: number;
  context?: ProtocolContext;
  data: TData;
}

export interface SessionReadyData extends Record<string, unknown> {
  sessionId: string;
  protocol: { min: number; max: number };
  server: { name: string; version: string; instanceId?: string };
  ghidra: { version: string };
  context: ProtocolContext;
  programSha256: string;
}

export interface EngineLimits {
  maxFrameBytes: number;
  maxTimeoutMs: number;
  maxListingItems: number;
  maxSymbolItems: number;
  maxReferenceItems: number;
  maxDecompileChars: number;
}

export interface SessionHelloResult extends Record<string, unknown> {
  selectedVersion: 1;
  sessionId: string;
  server: { name: string; version: string; instanceId?: string };
  ghidra: { version: string };
  capabilities: {
    methods: string[];
    events: string[];
    transports: Array<"stdio" | "loopback-http">;
    limits: EngineLimits;
  };
}

export interface ProgramSummaryResult extends Record<string, unknown> {
  name: string;
  executableFormat: string;
  languageId: string;
  compilerSpecId: string;
  imageBase: EngineAddress;
  minAddress: EngineAddress;
  maxAddress: EngineAddress;
  sha256: string;
  analysis: { complete: boolean; timedOut: boolean };
  memoryBlockCount: number;
  instructionCount: number;
  functionCount: number;
  symbolCount: number;
}

export interface ListingItem {
  address: EngineAddress;
  length: number;
  kind: "instruction" | "data" | "undefined";
  label?: string;
  mnemonic?: string;
  operands?: string;
  bytes?: string;
  flowType?: string;
  function?: string;
}

export interface ListingWindowResult extends Record<string, unknown> {
  items: ListingItem[];
  nextCursor?: string;
  previousCursor?: string;
  truncated: boolean;
}

export interface SymbolItem {
  name: string;
  address: EngineAddress;
  kind: "label" | "function" | "namespace" | "class" | "parameter" | "local" | "external" | "library" | "unknown";
  namespace?: string;
  source?: string;
  primary: boolean;
}

export interface SymbolsSearchResult extends Record<string, unknown> {
  items: SymbolItem[];
  nextCursor?: string;
  truncated: boolean;
}

export interface DecompileFunctionResult extends Record<string, unknown> {
  function: {
    name: string;
    entryPoint: EngineAddress;
    signature: string;
  };
  language: "c";
  text: string;
  truncated: boolean;
  warnings: string[];
}

export interface ReferenceItem {
  fromAddress: EngineAddress;
  toAddress: EngineAddress;
  type: string;
  operandIndex?: number;
  primary: boolean;
  external: boolean;
}

export interface ReferencesListResult extends Record<string, unknown> {
  items: ReferenceItem[];
  nextCursor?: string;
  truncated: boolean;
}

export interface DecompilerView {
  address: EngineAddress;
  functionName: string;
  signature: string;
  source: string;
  truncated: boolean;
  warnings: string[];
}

export interface ReferenceView {
  address: EngineAddress;
  incoming: number;
  outgoing: number;
  items: ReferenceItem[];
  truncated: boolean;
}

export type WorkbenchEngineMode = "synthetic" | "real";

export interface WorkbenchEventHandlers {
  analysis?: (state: AnalysisState) => void;
  engine?: () => void;
  warning?: (message: string) => void;
  stale?: (error: Error) => void;
}

export interface WorkbenchSubscription {
  close: () => void;
}

export type Capability =
  | "program.read"
  | "program.write"
  | "debug.trace.read"
  | "debug.target.control"
  | "filesystem.read"
  | "filesystem.write"
  | "network"
  | "process.exec";

export interface BackendState {
  mode: "synthetic-fixture" | "ghidra-headless";
  health: "not-configured" | "invalid-home" | "detected" | "connected";
  version: string;
  launcher: string;
  capabilities: Capability[];
}

export type DebugMode = "captured-trace" | "emulator" | "live-target";
export type DebugStatus = "disconnected" | "running" | "paused" | "past-snapshot";

export interface DebuggerState {
  mode: DebugMode;
  status: DebugStatus;
  trace: string;
  thread: string;
  frame: number;
  snapshot: number;
  latestSnapshot: number;
  staleMemory: boolean;
  writesEnabled: boolean;
  registers: Record<string, string>;
  stack: string[];
  watches: string[];
}

export type ScriptStatus = "success" | "backend-unavailable" | "approval-required" | "blocked";

export interface ScriptResult {
  status: ScriptStatus;
  output: string;
  requiredCapabilities: Capability[];
}

export interface ExtensionState {
  id: string;
  name: string;
  provider: string;
  enabled: boolean;
  requiresBackend: boolean;
  requiredCapabilities: Capability[];
  status: string;
}

export interface Command {
  id: string;
  label: string;
  detail: string;
  shortcut?: string;
  keywords: string[];
  run: () => void | Promise<void>;
}
