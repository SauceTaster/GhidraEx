package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisJob;
import dev.ghidraex.engine.AnalysisProgress;
import dev.ghidraex.engine.ListingWindow;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Reference;
import dev.ghidraex.engine.Symbol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncAnalysisLauncherTest {
    @Test
    void engineEntryIsDeferredAndPublishesASequencedTerminalState() {
        ManualExecutorService worker = new ManualExecutorService();
        StubEngine engine = new StubEngine();
        AtomicReference<AsyncAnalysisLauncher.Snapshot> latest = new AtomicReference<>();
        try (var launcher = new AsyncAnalysisLauncher(engine, worker, Runnable::run, () -> { })) {
            launcher.start(latest::set);

            assertEquals(0, engine.starts.get());
            assertEquals(AsyncAnalysisLauncher.State.STARTING, latest.get().state());
            worker.runAll();

            assertEquals(1, engine.starts.get());
            assertEquals(AsyncAnalysisLauncher.State.CURRENT, latest.get().state());
            assertEquals(3, latest.get().sequence());
        }
    }

    @Test
    void disposalCancelsQueuedLaunchAndSuppressesLaterUiCallbacks() {
        ManualExecutorService worker = new ManualExecutorService();
        StubEngine engine = new StubEngine();
        AtomicInteger observations = new AtomicInteger();
        var launcher = new AsyncAnalysisLauncher(engine, worker, Runnable::run, () -> { });
        launcher.start(ignored -> observations.incrementAndGet());

        launcher.close();
        worker.runAll();

        assertEquals(0, engine.starts.get());
        assertEquals(1, observations.get());
    }

    private static final class StubEngine implements AnalysisEngine {
        private final AtomicInteger starts = new AtomicInteger();

        @Override
        public ProgramInfo program() {
            return new ProgramInfo("test", "test", "test", "test", 0, "test");
        }

        @Override
        public int listingSize() {
            return 0;
        }

        @Override
        public ListingWindow listingWindow(int startIndex, int requestedCount) {
            return new ListingWindow(0, 0, List.of());
        }

        @Override
        public List<Symbol> searchSymbols(String query, int limit) {
            return List.of();
        }

        @Override
        public List<String> decompile(String symbolName, int maxLines) {
            return List.of();
        }

        @Override
        public List<Reference> references(long address, int limit) {
            return List.of();
        }

        @Override
        public AnalysisJob startAnalysis(Consumer<AnalysisProgress> progressListener) {
            starts.incrementAndGet();
            progressListener.accept(new AnalysisProgress(0.5, "Running", true));
            return new ImmediateJob();
        }
    }

    private static final class ImmediateJob implements AnalysisJob {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String title() {
            return "test";
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public CompletableFuture<Void> completion() {
            return completion;
        }
    }

    private static final class ManualExecutorService extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> copy = List.copyOf(tasks);
            tasks.clear();
            return copy;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            List<Runnable> copy = List.copyOf(tasks);
            tasks.clear();
            copy.forEach(Runnable::run);
        }
    }
}
