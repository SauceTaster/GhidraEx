package dev.ghidraex.engine;

import java.time.Duration;
import java.util.List;

public record AnalysisResult(
        List<SymbolDescriptor> symbols,
        int functionsDiscovered,
        int referencesRecovered,
        Duration elapsed
) {
    public AnalysisResult {
        symbols = List.copyOf(symbols);
    }
}
