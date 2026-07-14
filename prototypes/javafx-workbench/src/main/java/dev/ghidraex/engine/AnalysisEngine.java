package dev.ghidraex.engine;

import java.util.List;
import java.util.function.Consumer;

/**
 * Toolkit-neutral, viewport-oriented seam between an analysis runtime and a UI.
 * Implementations must never return Swing/JavaFX nodes or mutable Ghidra objects.
 */
public interface AnalysisEngine extends AutoCloseable {
    ProgramInfo program();

    int listingSize();

    /**
     * Fetches a bounded immutable window. Implementations may return fewer rows at
     * the end of the program and may cap oversized requests.
     */
    ListingWindow listingWindow(int startIndex, int requestedCount);

    List<Symbol> searchSymbols(String query, int limit);

    /** Returns at most {@code maxLines} immutable decompiler lines. */
    List<String> decompile(String symbolName, int maxLines);

    /** Returns at most {@code limit} references touching {@code address}. */
    List<Reference> references(long address, int limit);

    AnalysisJob startAnalysis(Consumer<AnalysisProgress> progressListener);

    @Override
    default void close() {
    }
}
