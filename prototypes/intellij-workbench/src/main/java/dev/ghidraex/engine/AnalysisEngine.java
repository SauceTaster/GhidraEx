package dev.ghidraex.engine;

import java.util.List;

/**
 * UI-toolkit-neutral boundary for a future Ghidra adapter.
 *
 * <p>The contract intentionally returns immutable, coarse-grained values rather than exposing
 * Ghidra domain objects. That keeps the UI usable in-process today and leaves room for a remote
 * engine tomorrow without introducing per-token RPC calls.</p>
 */
public interface AnalysisEngine {
    List<ProgramDescriptor> programs();

    List<SymbolDescriptor> symbols(String programId);

    int instructionCount(String programId);

    /** Fetches one bounded listing page; callers should not issue per-instruction engine calls. */
    List<ListingInstruction> listing(String programId, int startInstruction, int instructionCount);

    String renderListing(String programId, int startInstruction, int instructionCount);

    String decompile(String programId, long functionAddress);

    AnalysisResult analyze(String programId, AnalysisMonitor monitor);
}
