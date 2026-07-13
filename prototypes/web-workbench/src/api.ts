import type { AnalysisState, ListingWindow, SymbolRecord, WorkbenchSnapshot } from "./types";

const API_ROOT = "/api/v1";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_ROOT}${path}`, {
    ...init,
    headers: {
      Accept: "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!response.ok) throw new Error(`Workbench service returned ${response.status}`);
  return response.json() as Promise<T>;
}

export const workbenchApi = {
  snapshot: () => request<WorkbenchSnapshot>("/snapshot"),
  searchSymbols: (query: string) => request<{ query: string; symbols: SymbolRecord[] }>(`/symbols?q=${encodeURIComponent(query)}`),
  listing: (address: string, rows = 50) => request<ListingWindow>(`/listing?address=${encodeURIComponent(address)}&rows=${rows}`),
  startAnalysis: () => request<AnalysisState>("/analysis/start", { method: "POST" }),
  cancelAnalysis: () => request<AnalysisState>("/analysis/cancel", { method: "POST" }),
  eventSource: () => new EventSource(`${API_ROOT}/events`),
};
