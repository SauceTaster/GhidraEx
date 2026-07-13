package dev.ghidraex.engine;

public record AnalysisProgress(double fraction, String phase, boolean cancellable) {
    public AnalysisProgress {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
    }
}
