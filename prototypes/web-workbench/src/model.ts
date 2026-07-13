import type { Command, ListingRow } from "./types";

export function clampProgress(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

export function filterCommands(commands: Command[], query: string): Command[] {
  const needle = query.trim().toLocaleLowerCase();
  if (!needle) return commands;
  return commands
    .map((command) => {
      const label = command.label.toLocaleLowerCase();
      const haystack = `${label} ${command.detail} ${command.keywords.join(" ")}`.toLocaleLowerCase();
      const score = label.startsWith(needle) ? 0 : label.includes(needle) ? 1 : haystack.includes(needle) ? 2 : 99;
      return { command, score };
    })
    .filter(({ score }) => score < 99)
    .sort((a, b) => a.score - b.score || a.command.label.localeCompare(b.command.label))
    .map(({ command }) => command);
}

export function normalizeAddress(input: string): string | null {
  const clean = input.trim().toLocaleLowerCase().replaceAll("_", "");
  const hexadecimal = clean.startsWith("0x") ? clean.slice(2) : clean;
  if (!/^[0-9a-f]{1,16}$/.test(hexadecimal)) return null;
  return `0x${hexadecimal.padStart(8, "0")}`;
}

export function closestListingRow(rows: ListingRow[], requested: string): ListingRow | undefined {
  const target = Number.parseInt(requested.replace(/^0x/, ""), 16);
  if (!Number.isFinite(target)) return undefined;
  return rows.reduce<ListingRow | undefined>((best, row) => {
    const current = Number.parseInt(row.address.replace(/^0x/, ""), 16);
    if (!best) return row;
    const previous = Number.parseInt(best.address.replace(/^0x/, ""), 16);
    return Math.abs(current - target) < Math.abs(previous - target) ? row : best;
  }, undefined);
}
