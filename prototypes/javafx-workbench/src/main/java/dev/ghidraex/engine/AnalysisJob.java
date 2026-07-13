package dev.ghidraex.engine;

import java.util.concurrent.CompletableFuture;

public interface AnalysisJob {
    String id();

    String title();

    void cancel();

    boolean isCancelled();

    CompletableFuture<Void> completion();
}
