package dev.ghidraex.fx;

import dev.ghidraex.engine.SyntheticAnalysisEngine;
import dev.ghidraex.viewstate.ViewStateReducer;
import dev.ghidraex.viewstate.ViewStateReducer.AddressRef;
import dev.ghidraex.viewstate.ViewStateReducer.Completeness;
import dev.ghidraex.viewstate.ViewStateReducer.Freshness;
import dev.ghidraex.viewstate.ViewStateReducer.ListingRow;
import dev.ghidraex.viewstate.ViewStateReducer.ListingWindow;
import dev.ghidraex.viewstate.ViewStateReducer.LocationRef;
import dev.ghidraex.viewstate.ViewStateReducer.RowKind;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;
import dev.ghidraex.viewstate.ViewStateReducer.ViewRequest;
import dev.ghidraex.viewstate.ViewStateReducer.ViewSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncListingProjectionTest {
    private final ViewContext generation0 = new ViewContext("runtime", 3, "program", 0);
    private final LocationRef entry = location("ram", "1000");

    @Test
    void localReadsAndRequestSubmissionNeverInvokeProviderInline() {
        ManualExecutor worker = new ManualExecutor();
        AtomicInteger providerCalls = new AtomicInteger();
        BoundedListingRows localRows = new BoundedListingRows();
        ListingRow localRow = row("local", entry);
        localRows.replaceWith(List.of(localRow));

        try (var projection = projection(worker, Runnable::run, ignored -> { }, request -> {
            providerCalls.incrementAndGet();
            return window(request, "loaded");
        })) {
            projection.request(entry, 256);

            assertEquals(0, providerCalls.get(), "request() must only enqueue provider work");
            for (int read = 0; read < 1_000; read++) {
                assertEquals(localRow, localRows.get(0));
            }
            assertEquals(0, providerCalls.get(), "ObservableList.get() is local-only");

            worker.runNext();
            assertEquals(1, providerCalls.get());
            assertEquals(Freshness.CURRENT, projection.viewSnapshot().freshness());
        }
    }

    @Test
    void spine01OutOfOrderResponsesCannotRewindTheView() {
        ManualExecutor worker = new ManualExecutor();
        List<ViewSnapshot> rendered = new ArrayList<>();
        try (var projection = projection(worker, Runnable::run, rendered::add,
                request -> window(request, "result-" + request.requestId()))) {
            ViewRequest a = projection.request(entry, 64);
            ViewRequest b = projection.request(entry, 64);
            ViewRequest c = projection.request(entry, 64);

            worker.run(1); // B
            worker.run(0); // A
            worker.run(0); // C

            ViewSnapshot current = projection.viewSnapshot();
            assertEquals(Freshness.CURRENT, current.freshness());
            assertEquals(c.requestId(), current.displayed().requestId());
            assertEquals("result-" + c.requestId(), current.displayed().resultId());
            assertFalse(rendered.stream()
                    .filter(snapshot -> snapshot.displayed() != null)
                    .anyMatch(snapshot -> snapshot.displayed().requestId() == a.requestId()
                            || snapshot.displayed().requestId() == b.requestId()));
        }
    }

    @Test
    void spine02GenerationChangeKeepsOldWindowStaleAndRejectsLateCompletion() {
        ManualExecutor worker = new ManualExecutor();
        try (var projection = projection(worker, Runnable::run, ignored -> { },
                request -> window(request, "generation-" + request.context().contentGeneration()))) {
            projection.request(entry, 64);
            worker.runNext();
            String original = projection.viewSnapshot().displayed().resultId();

            projection.request(entry, 64); // old-generation refresh remains queued
            projection.acceptContentEvent(1, 1);
            assertEquals(Freshness.STALE, projection.viewSnapshot().freshness());
            worker.runNext();

            assertEquals(original, projection.viewSnapshot().displayed().resultId());
            assertEquals(Freshness.STALE, projection.viewSnapshot().freshness());

            ViewRequest current = projection.request(entry, 64);
            worker.runNext();
            assertEquals(1, current.context().contentGeneration());
            assertEquals(Freshness.CURRENT, projection.viewSnapshot().freshness());
            assertEquals("generation-1", projection.viewSnapshot().displayed().resultId());
        }
    }

    @Test
    void contentEventWithoutImmediateRefreshIsQueuedAsStaleUiState() {
        ManualExecutor worker = new ManualExecutor();
        QueueDispatcher ui = new QueueDispatcher();
        List<ViewSnapshot> rendered = new ArrayList<>();
        try (var projection = projection(worker, ui, rendered::add,
                request -> window(request, "generation-" + request.context().contentGeneration()))) {
            projection.request(entry, 32);
            worker.runNext();
            ui.drain();
            rendered.clear();

            projection.acceptContentEvent(1, 1);

            assertTrue(rendered.isEmpty());
            assertEquals(0, worker.size(), "an event publication must not start provider work");
            assertEquals(1, ui.size());
            ui.drain();
            assertEquals(1, rendered.size());
            assertEquals(Freshness.STALE, rendered.getFirst().freshness());
        }
    }

    @Test
    void resyncQuarantineBlocksLoadsUntilAValidatedSnapshotArrives() {
        ManualExecutor worker = new ManualExecutor();
        QueueDispatcher ui = new QueueDispatcher();
        try (var projection = projection(worker, ui, ignored -> { },
                request -> window(request, "generation-" + request.context().contentGeneration()))) {
            projection.request(entry, 32);
            worker.runNext();
            ui.drain();
            projection.acceptContentEvent(1, 0);
            projection.acceptContentEvent(3, 1);

            assertEquals(Freshness.RESYNCING, projection.viewSnapshot().freshness());
            assertTrue(projection.snapshot().resyncRequired());
            assertThrows(IllegalStateException.class, () -> projection.request(entry, 32));

            projection.applyValidatedSnapshot(new ViewContext("runtime", 3, "program", 1), 4);
            assertFalse(projection.snapshot().resyncRequired());
            assertEquals(Freshness.STALE, projection.viewSnapshot().freshness());
            projection.request(entry, 32);
            worker.runNext();
            assertEquals(Freshness.CURRENT, projection.viewSnapshot().freshness());
        }
    }

    @Test
    void validatedIdentitySwitchClearsTargetAndDisplayedWindow() {
        ManualExecutor worker = new ManualExecutor();
        QueueDispatcher ui = new QueueDispatcher();
        List<ViewSnapshot> rendered = new ArrayList<>();
        try (var projection = projection(worker, ui, rendered::add,
                request -> window(request, "old-program"))) {
            projection.request(entry, 32);
            worker.runNext();
            ui.drain();
            rendered.clear();

            projection.applyValidatedSnapshot(
                    new ViewContext("replacement-runtime", 1, "replacement-program", 0), 0);

            ViewSnapshot cleared = projection.viewSnapshot();
            assertEquals(Freshness.EMPTY, cleared.freshness());
            assertNull(cleared.target());
            assertNull(cleared.displayed());
            assertTrue(rendered.isEmpty());
            ui.drain();
            assertEquals(1, rendered.size());
            assertEquals(Freshness.EMPTY, rendered.getFirst().freshness());
            assertNull(rendered.getFirst().displayed());
        }
    }

    @Test
    void acceptedStateIsAppliedOnlyThroughUiDispatcher() {
        ManualExecutor worker = new ManualExecutor();
        QueueDispatcher ui = new QueueDispatcher();
        List<ViewSnapshot> rendered = new ArrayList<>();
        try (var projection = projection(worker, ui, rendered::add,
                request -> window(request, "result"))) {
            projection.request(entry, 32);
            worker.runNext();

            assertTrue(rendered.isEmpty());
            assertEquals(2, ui.size());
            ui.drain();
            assertFalse(rendered.isEmpty());
            assertTrue(rendered.stream().allMatch(snapshot -> snapshot.freshness() == Freshness.CURRENT));
        }
    }

    @Test
    void spine10DisposalInvalidatesAcceptedButQueuedUiCallbacks() {
        ManualExecutor worker = new ManualExecutor();
        QueueDispatcher ui = new QueueDispatcher();
        List<ViewSnapshot> rendered = new ArrayList<>();
        var projection = projection(worker, ui, rendered::add, request -> window(request, "late"));
        projection.request(entry, 32);
        worker.runNext();

        projection.close();
        ui.drain();

        assertTrue(rendered.isEmpty());
        assertTrue(projection.disposed());
        assertThrows(IllegalStateException.class, () -> projection.request(entry, 32));
    }

    @Test
    void providerFailureIsAnExplicitFailedState() {
        ManualExecutor worker = new ManualExecutor();
        try (var projection = projection(worker, Runnable::run, ignored -> { }, request -> {
            throw new IllegalStateException("fixture unavailable");
        })) {
            projection.request(entry, 32);
            worker.runNext();

            assertEquals(Freshness.FAILED, projection.viewSnapshot().freshness());
            assertTrue(projection.viewSnapshot().detail().contains("fixture unavailable"));
        }
    }

    @Test
    void saturatedProviderExecutorFailsExplicitlyWithoutCallingProvider() {
        QueueDispatcher ui = new QueueDispatcher();
        List<ViewSnapshot> rendered = new ArrayList<>();
        AtomicInteger providerCalls = new AtomicInteger();
        Executor saturated = command -> {
            throw new RejectedExecutionException("provider queue is full");
        };
        try (var projection = projection(saturated, ui, rendered::add, request -> {
            providerCalls.incrementAndGet();
            return window(request, "unreachable");
        })) {
            projection.request(entry, 32);

            assertEquals(0, providerCalls.get());
            assertEquals(Freshness.FAILED, projection.viewSnapshot().freshness());
            assertTrue(projection.viewSnapshot().detail().contains("provider queue is full"));
            assertTrue(rendered.isEmpty());
            ui.drain();
            assertTrue(rendered.stream().allMatch(snapshot -> snapshot.freshness() == Freshness.FAILED));
        }
    }

    @Test
    void concurrentCloseShutsOwnedWorkerOnlyOnce() throws Exception {
        ManualExecutor worker = new ManualExecutor();
        AtomicInteger workerCloses = new AtomicInteger();
        var projection = new AsyncListingProjection(
                generation0,
                request -> window(request, "unused"),
                worker,
                Runnable::run,
                ignored -> { },
                workerCloses::incrementAndGet);
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> await(start, projection::close));
        Thread second = new Thread(() -> await(start, projection::close));
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertTrue(projection.disposed());
        assertEquals(1, workerCloses.get());
    }

    @Test
    void boundedLocalRowsRejectUnboundedProjection() {
        BoundedListingRows localRows = new BoundedListingRows();
        List<ListingRow> tooMany = java.util.stream.IntStream.rangeClosed(0, 4_096)
                .mapToObj(index -> row("row-" + index, entry))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> localRows.replaceWith(tooMany));
    }

    @Test
    void fixtureAdapterPreservesOffcutAnchorInABoundedSemanticWindow() {
        try (var engine = new SyntheticAnalysisEngine()) {
            var provider = new SyntheticSemanticListingProvider(engine, EngineSessionSnapshot.capture(engine));
            LocationRef offcut = provider.locationFor(engine.program().imageBase() + 1);
            ViewRequest request = new ViewRequest(
                    AsyncListingProjection.VIEW_ID,
                    1,
                    provider.initialContext(),
                    offcut,
                    16);

            ListingWindow result = provider.load(request);

            assertTrue(offcut.offcut());
            assertEquals(1, offcut.byteOffset());
            assertEquals(offcut, result.anchor());
            assertTrue(result.rows().size() <= 16);
            assertEquals(offcut.containingAddress(), result.rows().getFirst().location().containingAddress());
        }
    }

    private AsyncListingProjection projection(
            Executor worker,
            AsyncListingProjection.UiDispatcher ui,
            java.util.function.Consumer<ViewSnapshot> observer,
            AsyncListingProjection.Provider provider) {
        return new AsyncListingProjection(generation0, provider, worker, ui, observer);
    }

    private static ListingWindow window(ViewRequest request, String resultId) {
        return new ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                resultId,
                request.anchor(),
                List.of(row(resultId + "-row", request.anchor())),
                Completeness.COMPLETE,
                List.of());
    }

    private static ListingRow row(String id, LocationRef location) {
        return new ListingRow(
                id,
                RowKind.INSTRUCTION,
                location,
                Map.of("address", location.requestedAddress().display(), "mnemonic", "mov"));
    }

    private static LocationRef location(String space, String offset) {
        AddressRef address = new AddressRef(space, 1, offset, space + ":" + offset);
        return new LocationRef(address, address, 0, "mnemonic");
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            run(0);
        }

        void run(int index) {
            tasks.remove(index).run();
        }

        int size() {
            return tasks.size();
        }
    }

    private static final class QueueDispatcher implements AsyncListingProjection.UiDispatcher {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void dispatch(Runnable action) {
            tasks.add(action);
        }

        int size() {
            return tasks.size();
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }

    private static void await(CountDownLatch start, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
