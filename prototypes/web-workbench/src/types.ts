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

export interface Command {
  id: string;
  label: string;
  detail: string;
  shortcut?: string;
  keywords: string[];
  run: () => void | Promise<void>;
}
