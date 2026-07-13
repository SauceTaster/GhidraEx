package dev.ghidraex.engine;

/** One immutable display row returned as part of a coarse listing page. */
public record ListingInstruction(
        int ordinal,
        long address,
        String bytes,
        String mnemonic,
        String operands,
        String label
) {
}
