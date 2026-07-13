import { describe, expect, it, vi } from "vitest";
import { clampProgress, closestListingRow, filterCommands, normalizeAddress } from "./model";
import type { Command, ListingRow } from "./types";

const commands: Command[] = [
  { id: "analyze", label: "Start analysis", detail: "Run analyzers", keywords: ["scan"], run: vi.fn() },
  { id: "goto", label: "Go to address", detail: "Navigate", keywords: ["jump"], run: vi.fn() },
];

describe("workbench model", () => {
  it("clamps progress from an untrusted event stream", () => {
    expect(clampProgress(-1)).toBe(0);
    expect(clampProgress(43.6)).toBe(44);
    expect(clampProgress(200)).toBe(100);
    expect(clampProgress(Number.NaN)).toBe(0);
  });

  it("ranks command labels before keyword matches", () => {
    expect(filterCommands(commands, "go").map((command) => command.id)).toEqual(["goto"]);
    expect(filterCommands(commands, "scan").map((command) => command.id)).toEqual(["analyze"]);
  });

  it("normalizes valid addresses and rejects arbitrary input", () => {
    expect(normalizeAddress("4010_FF")).toBe("0x004010ff");
    expect(normalizeAddress("0xDEADbeef")).toBe("0xdeadbeef");
    expect(normalizeAddress("main + 4")).toBeNull();
  });

  it("finds the closest row for coarse viewport navigation", () => {
    const rows = [
      { address: "0x00401000" },
      { address: "0x00401008" },
    ] as ListingRow[];
    expect(closestListingRow(rows, "0x00401006")?.address).toBe("0x00401008");
  });
});
