package dev.ghidraex.engine;

/** Adapter point for progress and cooperative cancellation, with no UI dependencies. */
public interface AnalysisMonitor {
    void checkCancelled();

    void report(double fraction, String phase, String detail);
}
