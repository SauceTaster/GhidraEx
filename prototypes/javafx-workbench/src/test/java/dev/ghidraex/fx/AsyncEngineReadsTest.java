package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisJob;
import dev.ghidraex.engine.AnalysisProgress;
import dev.ghidraex.engine.ListingWindow;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Reference;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.viewstate.ContextualReadSlot.Freshness;
import dev.ghidraex.viewstate.ContextualReadSlot.Snapshot;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncEngineReadsTest {
    private static final ViewContext GENERATION_ZERO =
            new ViewContext("runtime", 1, "program", 0);

    @Test
    void reverseCompletionCannotReplaceTheLatestSymbolQuery() throws Exception {
        CountDownLatch oldStarted = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        TestEngine engine = new TestEngine();
        engine.symbols = (query, limit) -> {
            if (query.equals("old")) {
                oldStarted.countDown();
                awaitIgnoringInterrupts(releaseOld);
            }
            return List.of(symbol(query));
        };
        ExecutorService worker = java.util.concurrent.Executors.newFixedThreadPool(2);
        AtomicReference<Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>> latest =
                new AtomicReference<>();
        CountDownLatch newAccepted = new CountDownLatch(1);
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run,
                worker::shutdownNow)) {
            var handle = reads.openSymbolSearch(snapshot -> {
                latest.set(snapshot);
                if (snapshot.displayed() != null
                        && snapshot.displayed().query().text().equals("new")
                        && snapshot.freshness() == Freshness.CURRENT) {
                    newAccepted.countDown();
                }
            });

            handle.request(AsyncEngineReads.SymbolQuery.from("old"));
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS));
            handle.request(AsyncEngineReads.SymbolQuery.from("new"));
            assertTrue(newAccepted.await(5, TimeUnit.SECONDS));
            releaseOld.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals("new", latest.get().displayed().query().text());
            assertEquals("new", latest.get().displayed().value().symbols().getFirst().name());
        } finally {
            releaseOld.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void generationAdvanceRejectsLateProviderDataAndRefreshesTheLastQuery() throws Exception {
        CountDownLatch generationZeroStarted = new CountDownLatch(1);
        CountDownLatch releaseGenerationZero = new CountDownLatch(1);
        AtomicInteger call = new AtomicInteger();
        TestEngine engine = new TestEngine();
        engine.symbols = (query, limit) -> {
            int sequence = call.incrementAndGet();
            if (sequence == 1) {
                generationZeroStarted.countDown();
                awaitIgnoringInterrupts(releaseGenerationZero);
                return List.of(symbol("generation-zero"));
            }
            return List.of(symbol("generation-one"));
        };
        ExecutorService worker = java.util.concurrent.Executors.newFixedThreadPool(2);
        CountDownLatch generationOneAccepted = new CountDownLatch(1);
        AtomicReference<Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>> latest =
                new AtomicReference<>();
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run,
                worker::shutdownNow)) {
            var handle = reads.openSymbolSearch(snapshot -> {
                latest.set(snapshot);
                if (snapshot.displayed() != null
                        && snapshot.displayed().context().contentGeneration() == 1
                        && snapshot.freshness() == Freshness.CURRENT) {
                    generationOneAccepted.countDown();
                }
            });
            handle.request(AsyncEngineReads.SymbolQuery.from("same-query"));
            assertTrue(generationZeroStarted.await(5, TimeUnit.SECONDS));

            reads.contentGenerationAdvanced();
            assertTrue(generationOneAccepted.await(5, TimeUnit.SECONDS));
            releaseGenerationZero.countDown();
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(1, latest.get().displayed().context().contentGeneration());
            assertEquals("generation-one", latest.get().displayed().value().symbols().getFirst().name());
        } finally {
            releaseGenerationZero.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void failureAndOversizePayloadAreExplicitAndNeverReachControls() {
        ManualExecutorService worker = new ManualExecutorService();
        TestEngine engine = new TestEngine();
        engine.symbols = (query, limit) -> List.of(new Symbol(
                "x".repeat(AsyncEngineReads.MAX_SYMBOL_NAME_CHARS + 1),
                0x1000,
                Symbol.Kind.FUNCTION,
                "fixture"));
        AtomicReference<Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>> latest =
                new AtomicReference<>();
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run, () -> { })) {
            var handle = reads.openSymbolSearch(latest::set);
            handle.request(AsyncEngineReads.SymbolQuery.from("oversize"));
            worker.runAll();

            assertEquals(Freshness.FAILED, latest.get().freshness());
            assertTrue(latest.get().detail().contains("transport text bound"));
            assertEquals(AsyncEngineReads.MAX_SYMBOL_RESULTS + 1, engine.lastSymbolLimit.get());
            assertEquals(0, reads.symbolCacheSize());
        }
    }

    @Test
    void disposalSuppressesAProviderCompletionAlreadyInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestEngine engine = new TestEngine();
        engine.symbols = (query, limit) -> {
            started.countDown();
            awaitIgnoringInterrupts(release);
            return List.of(symbol("late"));
        };
        ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        AtomicInteger observations = new AtomicInteger();
        var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run, worker::shutdownNow);
        var handle = reads.openSymbolSearch(ignored -> observations.incrementAndGet());
        handle.request(AsyncEngineReads.SymbolQuery.from("blocked"));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        int beforeClose = observations.get();

        handle.close();
        release.countDown();
        worker.shutdown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(beforeClose, observations.get());
        assertTrue(handle.snapshot().disposed());
        assertThrows(IllegalStateException.class,
                () -> handle.request(AsyncEngineReads.SymbolQuery.from("again")));
        reads.close();
    }

    @Test
    void rapidTypeAheadPurgesCancelledQueueEntriesAndAdmitsTheLatestRequest() throws Exception {
        List<CountDownLatch> started = List.of(
                new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1));
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        AtomicInteger providerSequence = new AtomicInteger();
        TestEngine engine = new TestEngine();
        engine.symbols = (query, limit) -> {
            int index = providerSequence.getAndIncrement();
            if (index < started.size()) {
                started.get(index).countDown();
                awaitIgnoringInterrupts(releaseBlocked);
            }
            return List.of(symbol(query));
        };
        ExecutorService worker = AsyncEngineReads.newBoundedWorker(runnable -> {
            Thread thread = new Thread(runnable, "type-ahead-test");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch latestAccepted = new CountDownLatch(1);
        AtomicReference<Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>> latest =
                new AtomicReference<>();
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run,
                worker::shutdownNow)) {
            var handle = reads.openSymbolSearch(snapshot -> {
                latest.set(snapshot);
                if (snapshot.displayed() != null
                        && snapshot.displayed().query().text().equals("query-39")
                        && snapshot.freshness() == Freshness.CURRENT) {
                    latestAccepted.countDown();
                }
            });

            for (int index = 0; index < 3; index++) {
                handle.request(AsyncEngineReads.SymbolQuery.from("query-" + index));
                assertTrue(started.get(index).await(5, TimeUnit.SECONDS));
            }
            for (int index = 3; index < 40; index++) {
                handle.request(AsyncEngineReads.SymbolQuery.from("query-" + index));
            }
            releaseBlocked.countDown();

            assertTrue(latestAccepted.await(5, TimeUnit.SECONDS));
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("query-39", latest.get().displayed().query().text());
            assertEquals(Freshness.CURRENT, latest.get().freshness());
            assertTrue(providerSequence.get() <= 4, "cancelled queued reads must not enter the provider");
        } finally {
            releaseBlocked.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void boundedCacheEvictsLeastRecentlyUsedEntries() {
        var cache = new BoundedResultCache<String, Integer>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(1, cache.get("a").orElseThrow());
        cache.put("c", 3);

        assertTrue(cache.get("b").isEmpty());
        assertEquals(2, cache.size());
        assertEquals(java.util.Set.of("a", "c"), cache.snapshot().keySet());
    }

    @Test
    void decompilerAndReferenceAdaptersUseLookaheadButPublishOnlyBoundedPayloads() {
        ManualExecutorService worker = new ManualExecutorService();
        TestEngine engine = new TestEngine();
        engine.decompiler = (symbol, limit) -> java.util.stream.IntStream.range(0, limit)
                .mapToObj(index -> "line " + index)
                .toList();
        engine.references = (address, limit) -> java.util.stream.IntStream.range(0, limit)
                .mapToObj(index -> new Reference(address - index - 1L, address, Reference.Kind.CALL))
                .toList();
        AtomicReference<Snapshot<AsyncEngineReads.DecompilerQuery, AsyncEngineReads.DecompiledFunction>>
                decompiler = new AtomicReference<>();
        AtomicReference<Snapshot<AsyncEngineReads.ReferenceQuery, AsyncEngineReads.ReferenceSet>>
                references = new AtomicReference<>();
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run, () -> { })) {
            var decompilerHandle = reads.openDecompiler(decompiler::set);
            var referenceHandle = reads.openReferences(references::set);

            decompilerHandle.request(AsyncEngineReads.DecompilerQuery.from("function"));
            referenceHandle.request(AsyncEngineReads.ReferenceQuery.at(0x2000));
            worker.runAll();

            assertEquals(Freshness.PARTIAL, decompiler.get().freshness());
            assertEquals(AsyncEngineReads.MAX_DECOMPILER_LINES,
                    decompiler.get().displayed().value().lines().size());
            assertEquals(AsyncEngineReads.MAX_DECOMPILER_LINES + 1, engine.lastDecompilerLimit.get());
            assertEquals(Freshness.PARTIAL, references.get().freshness());
            assertEquals(AsyncEngineReads.MAX_REFERENCES,
                    references.get().displayed().value().references().size());
            assertEquals(AsyncEngineReads.MAX_REFERENCES + 1, engine.lastReferenceLimit.get());
        }
    }

    @Test
    void uiSubmissionDuringResyncRemainsQuarantinedWithoutThrowing() {
        ManualExecutorService worker = new ManualExecutorService();
        TestEngine engine = new TestEngine();
        AtomicReference<Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>> latest =
                new AtomicReference<>();
        try (var reads = new AsyncEngineReads(engine, GENERATION_ZERO, worker, Runnable::run, () -> { })) {
            var handle = reads.openSymbolSearch(latest::set);
            reads.applyValidatedContext(GENERATION_ZERO, true, false);

            assertTrue(!handle.submit(AsyncEngineReads.SymbolQuery.from("blocked")));
            assertEquals(Freshness.RESYNCING, latest.get().freshness());
            worker.runAll();
            assertEquals(0, engine.symbolCalls.get());
        }
    }

    private static Symbol symbol(String name) {
        return new Symbol(name, 0x1000, Symbol.Kind.FUNCTION, "fixture");
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TestEngine implements AnalysisEngine {
        private static final ProgramInfo PROGRAM = new ProgramInfo(
                "test", "test.bin", "test", "test", 0x1000, "program");

        private BiFunction<String, Integer, List<Symbol>> symbols = (query, limit) -> List.of();
        private BiFunction<String, Integer, List<String>> decompiler = (query, limit) -> List.of();
        private ReferenceLoader references = (address, limit) -> List.of();
        private final AtomicInteger symbolCalls = new AtomicInteger();
        private final AtomicInteger lastSymbolLimit = new AtomicInteger();
        private final AtomicInteger lastDecompilerLimit = new AtomicInteger();
        private final AtomicInteger lastReferenceLimit = new AtomicInteger();

        @Override
        public ProgramInfo program() {
            return PROGRAM;
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
            symbolCalls.incrementAndGet();
            lastSymbolLimit.set(limit);
            return symbols.apply(query, limit);
        }

        @Override
        public List<String> decompile(String symbolName, int maxLines) {
            lastDecompilerLimit.set(maxLines);
            return decompiler.apply(symbolName, maxLines);
        }

        @Override
        public List<Reference> references(long address, int limit) {
            lastReferenceLimit.set(limit);
            return references.load(address, limit);
        }

        @Override
        public AnalysisJob startAnalysis(Consumer<AnalysisProgress> progressListener) {
            throw new UnsupportedOperationException("not used by contextual read tests");
        }
    }

    @FunctionalInterface
    private interface ReferenceLoader {
        List<Reference> load(long address, int limit);
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
