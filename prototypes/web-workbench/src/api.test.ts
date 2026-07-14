import { describe, expect, it, vi } from "vitest";
import { EngineProtocolError, RealGatewayWorkbenchApi } from "./api";
import type { ProtocolContext, ProtocolRequest } from "./types";

const CONTEXT: ProtocolContext = { programId: "program-canonical", revision: 0 };
const SHA256 = "a".repeat(64);

const READY_EVENT = {
  kind: "event",
  version: 1,
  event: "session.ready",
  sequence: 1,
  context: CONTEXT,
  data: {
    sessionId: "session-1",
    protocol: { min: 1, max: 1 },
    server: { name: "ghidraex-engine", version: "0.1.0" },
    ghidra: { version: "12.1.2" },
    context: CONTEXT,
    programSha256: SHA256,
  },
};

const HELLO_RESULT = {
  selectedVersion: 1,
  sessionId: "session-1",
  server: { name: "ghidraex-engine", version: "0.1.0" },
  ghidra: { version: "12.1.2" },
  capabilities: {
    methods: ["program.summary", "listing.window", "symbols.search", "decompile.function", "references.list"],
    events: ["analysis.progress", "engine.warning", "session.closing"],
    transports: ["stdio", "loopback-http"],
    limits: {
      maxFrameBytes: 2_097_152,
      maxTimeoutMs: 30_000,
      maxListingItems: 1_000,
      maxSymbolItems: 500,
      maxReferenceItems: 2_000,
      maxDecompileChars: 500_000,
    },
  },
};

const RESULTS: Record<string, Record<string, unknown>> = {
  "session.hello": HELLO_RESULT,
  "program.summary": {
    name: "sample.bin",
    executableFormat: "Executable and Linking Format (ELF)",
    languageId: "x86:LE:64:default",
    compilerSpecId: "gcc",
    imageBase: "ram:00401000",
    minAddress: "ram:00401000",
    maxAddress: "ram:00401fff",
    sha256: SHA256,
    analysis: { complete: true, timedOut: false },
    memoryBlockCount: 4,
    instructionCount: 12_345,
    functionCount: 17,
    symbolCount: 29,
  },
  "listing.window": {
    items: [{
      address: "ram:00401000",
      length: 1,
      kind: "instruction",
      mnemonic: "CALL",
      operands: "ram:00401100",
      bytes: "e8",
      flowType: "UNCONDITIONAL_CALL",
      function: "entry",
    }],
    truncated: false,
  },
  "symbols.search": {
    items: [{
      name: "entry",
      address: "ram:00401000",
      kind: "function",
      namespace: "Global",
      source: "ANALYSIS",
      primary: true,
    }],
    truncated: false,
  },
  "decompile.function": {
    function: { name: "entry", entryPoint: "ram:00401000", signature: "void entry(void)" },
    language: "c",
    text: "void entry(void) {\n  return;\n}",
    truncated: false,
    warnings: [],
  },
  "references.list": {
    items: [
      { fromAddress: "ram:00400000", toAddress: "ram:00401000", type: "CALL", primary: true, external: false },
      { fromAddress: "ram:00401000", toAddress: "ram:00401100", type: "CALL", operandIndex: 0, primary: true, external: false },
    ],
    truncated: false,
  },
};

interface GatewayOptions {
  contextOverride?: ProtocolContext;
  helloResult?: Record<string, unknown>;
  failMethod?: string;
}

function json(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function events(...values: unknown[]): Response {
  return new Response(`${values.map((value) => JSON.stringify(value)).join("\n")}\n`, {
    status: 200,
    headers: {
      "Content-Type": "application/x-ndjson; charset=utf-8",
      "X-GhidraEx-Oldest-Sequence": "1",
      "X-GhidraEx-Latest-Sequence": String(values.length),
    },
  });
}

function gateway(options: GatewayOptions = {}): { fetcher: typeof fetch; requests: ProtocolRequest[] } {
  const requests: ProtocolRequest[] = [];
  const implementation = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    if (String(input) === "/engine/v1/events") return events(READY_EVENT);
    expect(String(input)).toBe("/engine/v1/requests");
    const request = JSON.parse(String(init?.body)) as ProtocolRequest;
    requests.push(request);
    if (request.method === options.failMethod) {
      return json({
        kind: "response",
        version: 1,
        id: request.id,
        method: request.method,
        ok: false,
        error: { code: "GHIDRA_FAILURE", message: "domain read failed", retryable: false },
      });
    }
    const result = request.method === "session.hello" && options.helloResult
      ? options.helloResult
      : RESULTS[request.method];
    if (!result) throw new Error(`Unexpected method ${request.method}`);
    return json({
      kind: "response",
      version: 1,
      id: request.id,
      method: request.method,
      ok: true,
      ...(request.method === "session.hello" ? {} : { context: options.contextOverride ?? request.context }),
      result,
    });
  };
  return { fetcher: vi.fn(implementation) as unknown as typeof fetch, requests };
}

describe("real protocol-v1 workbench client", () => {
  it("binds the browser fetch receiver when no test transport is injected", async () => {
    const fake = gateway();
    const receiverCheckedFetch = vi.fn(function (
      this: unknown,
      input: RequestInfo | URL,
      init?: RequestInit,
    ): Promise<Response> {
      if (this !== globalThis) throw new TypeError("illegal fetch receiver");
      return fake.fetcher(input, init);
    });
    vi.stubGlobal("fetch", receiverCheckedFetch);
    try {
      const api = new RealGatewayWorkbenchApi();
      await expect(api.snapshot()).resolves.toMatchObject({
        backend: { mode: "ghidra-headless", health: "connected" },
      });
      expect(receiverCheckedFetch).toHaveBeenCalled();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("bootstraps from session.ready, negotiates without context, and maps bounded real reads", async () => {
    const fake = gateway();
    const api = new RealGatewayWorkbenchApi(fake.fetcher);

    const snapshot = await api.snapshot();

    expect(fake.requests.map((request) => request.method)).toEqual([
      "session.hello",
      "program.summary",
      "symbols.search",
      "listing.window",
      "decompile.function",
      "references.list",
    ]);
    expect(fake.requests[0]).not.toHaveProperty("context");
    expect(fake.requests.slice(1).every((request) => JSON.stringify(request.context) === JSON.stringify(CONTEXT))).toBe(true);
    expect(snapshot.backend).toMatchObject({ mode: "ghidra-headless", health: "connected", version: "12.1.2" });
    expect(snapshot.project.imageBase).toBe("ram:00401000");
    expect(snapshot.listingInfo.totalInstructions).toBe(12_345);
    expect(snapshot.listing[0]).toMatchObject({ address: "ram:00401000", flow: "call" });
    expect(snapshot.inspector).toMatchObject({ functionName: "entry", xrefsIn: 1, xrefsOut: 1 });
    expect(snapshot.decompiler).toContain("void entry(void)");

    for (const invocation of vi.mocked(fake.fetcher).mock.calls) {
      expect(new Headers(invocation[1]?.headers).has("Authorization")).toBe(false);
      expect(String(invocation[0])).not.toContain("token");
    }
  });

  it("rejects a read response for another revision instead of mixing identities", async () => {
    const fake = gateway({ contextOverride: { ...CONTEXT, revision: 1 } });
    const api = new RealGatewayWorkbenchApi(fake.fetcher);

    await expect(api.snapshot()).rejects.toThrow("does not match the active program view");
    expect(fake.requests.map((request) => request.method)).toEqual(["session.hello", "program.summary"]);
  });

  it("surfaces canonical engine errors without a synthetic fallback", async () => {
    const fake = gateway({ failMethod: "program.summary" });
    const api = new RealGatewayWorkbenchApi(fake.fetcher);

    const read = api.snapshot();
    await expect(read).rejects.toBeInstanceOf(EngineProtocolError);
    await expect(read).rejects.toMatchObject({
      name: "EngineProtocolError",
      code: "GHIDRA_FAILURE",
      retryable: false,
      message: "domain read failed",
    });
    expect(fake.requests.map((request) => request.method)).toEqual(["session.hello", "program.summary"]);
  });

  it("rejects unknown fields in negotiated capability DTOs", async () => {
    const fake = gateway({ helloResult: { ...HELLO_RESULT, accidental: true } });
    const api = new RealGatewayWorkbenchApi(fake.fetcher);

    await expect(api.snapshot()).rejects.toThrow("unknown field");
    expect(fake.requests.map((request) => request.method)).toEqual(["session.hello"]);
  });

  it("uses authenticated-fetch NDJSON subscriptions and detects sequence gaps", async () => {
    let eventCalls = 0;
    const fake = gateway();
    const base = fake.fetcher;
    const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      if (String(input) !== "/engine/v1/events") return base(input, init);
      eventCalls += 1;
      if (eventCalls === 1) return events(READY_EVENT);
      return events({
        kind: "event",
        version: 1,
        event: "analysis.progress",
        sequence: 3,
        context: CONTEXT,
        data: { phase: "analysis", completed: 1, total: 2 },
      });
    }) as unknown as typeof fetch;
    const api = new RealGatewayWorkbenchApi(fetcher);
    await api.snapshot();
    const stale = vi.fn();

    const subscription = api.subscribe({ stale });
    await vi.waitFor(() => expect(stale).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining("skipped a sequence") })), { timeout: 1_000 });
    subscription.close();
  });
});
