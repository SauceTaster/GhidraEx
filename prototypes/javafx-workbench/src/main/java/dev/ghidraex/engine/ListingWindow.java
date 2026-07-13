package dev.ghidraex.engine;

import java.util.List;
import java.util.Objects;

/** Immutable listing document suitable for an in-process call or batched RPC. */
public record ListingWindow(int startIndex, int totalInstructions, List<DisassemblyLine> rows) {
    public ListingWindow {
        if (startIndex < 0 || totalInstructions < 0 || startIndex > totalInstructions) {
            throw new IllegalArgumentException("Invalid listing window extent");
        }
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (rows.size() > totalInstructions - startIndex) {
            throw new IllegalArgumentException("Listing rows extend beyond the program");
        }
    }

    public int endIndexExclusive() {
        return startIndex + rows.size();
    }
}
