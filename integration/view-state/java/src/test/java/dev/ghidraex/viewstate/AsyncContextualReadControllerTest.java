package dev.ghidraex.viewstate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncContextualReadControllerTest {
    private final ViewStateReducer.ViewContext context =
            new ViewStateReducer.ViewContext("runtime", 1, "program", 7);

    @Test
    void supersedingAQueuedReadCancelsProviderEntry() {
        var worker = new ManualExecutorService();
        var ui = new ManualDispatcher();
        var calls = new ArrayList<String>();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot,
                () -> context,
                () -> false,
                request -> {
                    calls.add(request.query());
                    return loaded(request.query());
                },
                worker,
                ui,
                ignored -> {});

        controller.request("old");
        controller.request("new");
        worker.runAll();
        ui.runAll();

        assertEquals(List.of("new"), calls);
        assertEquals(List.of("new"), slot.snapshot().displayed().value());
        assertEquals(ContextualReadSlot.Freshness.CURRENT, slot.snapshot().freshness());
    }

    @Test
    void loaderFailurePublishesAnExplicitFailedSnapshot() {
        var worker = new ManualExecutorService();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ContextualReadSlot.Snapshot<String, List<String>>>();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot,
                () -> context,
                () -> false,
                request -> { throw new IllegalStateException("decompiler crashed"); },
                worker,
                ui,
                observed::add);

        controller.request("decode");
        worker.runAll();
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, slot.snapshot().freshness());
        assertTrue(slot.snapshot().detail().contains("decompiler crashed"));
        assertEquals(ContextualReadSlot.Freshness.FAILED, observed.getLast().freshness());
    }

    @Test
    void boundedWorkerRejectionFailsRatherThanGrowingAQueue() {
        var worker = new RejectingExecutorService();
        var ui = new ManualDispatcher();
        var outcomes = new ArrayList<AsyncContextualReadController.Outcome<String, List<String>>>();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot, () -> context, () -> false, request -> loaded(request.query()), worker, ui,
                ignored -> {}, outcomes::add);

        controller.request("decode");
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, slot.snapshot().freshness());
        assertEquals(ContextualReadSlot.Acceptance.ACCEPTED, outcomes.getFirst().acceptance());
        assertTrue(slot.snapshot().detail().contains("rejected"));
    }

    @Test
    void faultyOutcomeHookCannotSuppressTheAuthoritativeUiTransition() {
        var worker = new ManualExecutorService();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ContextualReadSlot.Snapshot<String, List<String>>>();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot,
                () -> context,
                () -> false,
                request -> { throw new IllegalStateException("provider failed"); },
                worker,
                ui,
                observed::add,
                ignored -> { throw new IllegalStateException("release hook failed"); });

        controller.request("decode");
        worker.runAll();
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, slot.snapshot().freshness());
        assertEquals(ContextualReadSlot.Freshness.FAILED, observed.getLast().freshness());
    }

    @Test
    void dispatcherRejectionTerminatesTheRequestBeforeProviderSubmission() {
        var worker = new ManualExecutorService();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot,
                () -> context,
                () -> false,
                request -> loaded(request.query()),
                worker,
                update -> { throw new IllegalStateException("EDT unavailable"); },
                ignored -> {});

        assertThrows(IllegalStateException.class, () -> controller.request("decode"));
        assertEquals(0, worker.tasks.size());
        assertEquals(ContextualReadSlot.Freshness.FAILED, slot.snapshot().freshness());
        assertTrue(slot.snapshot().detail().contains("dispatcher"));
    }

    @Test
    void resyncTransitionCancelsQueuedWorkAndPublishesQuarantine() {
        var worker = new ManualExecutorService();
        var ui = new ManualDispatcher();
        var resync = new java.util.concurrent.atomic.AtomicBoolean();
        var calls = new AtomicInteger();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot, () -> context, resync::get,
                request -> {
                    calls.incrementAndGet();
                    return loaded(request.query());
                },
                worker,
                ui,
                ignored -> {});

        controller.request("decode");
        resync.set(true);
        controller.contextChanged();
        worker.runAll();
        ui.runAll();

        assertEquals(0, calls.get());
        assertEquals(ContextualReadSlot.Freshness.RESYNCING, slot.snapshot().freshness());
        assertThrows(IllegalStateException.class, () -> controller.request("blocked"));
    }

    @Test
    void disposalSkipsQueuedProviderAndSuppressesUiCallbacks() {
        var worker = new ManualExecutorService();
        var ui = new ManualDispatcher();
        var calls = new AtomicInteger();
        var observed = new AtomicInteger();
        var slot = slot();
        var controller = new AsyncContextualReadController<>(
                slot, () -> context, () -> false,
                request -> {
                    calls.incrementAndGet();
                    return loaded(request.query());
                },
                worker,
                ui,
                ignored -> observed.incrementAndGet());

        controller.request("decode");
        controller.close();
        worker.runAll();
        ui.runAll();

        assertEquals(0, calls.get());
        assertEquals(0, observed.get());
        assertTrue(slot.snapshot().disposed());
        assertThrows(IllegalStateException.class, () -> controller.request("again"));
    }

    private ContextualReadSlot<String, List<String>> slot() {
        return new ContextualReadSlot<>("decompiler", context, List::copyOf);
    }

    private ContextualReadSlot.LoadedResult<String, List<String>> loaded(String query) {
        return new ContextualReadSlot.LoadedResult<>(
                context,
                query,
                "result-" + query,
                List.of(query),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of());
    }

    private static final class ManualDispatcher implements AsyncContextualReadController.UiDispatcher {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void dispatch(Runnable update) {
            tasks.add(update);
        }

        private void runAll() {
            var copy = List.copyOf(tasks);
            tasks.clear();
            copy.forEach(Runnable::run);
        }
    }

    private static class ManualExecutorService extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var copy = List.copyOf(tasks);
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
            if (shutdown) throw new RejectedExecutionException("shutdown");
            tasks.add(command);
        }

        private void runAll() {
            var copy = List.copyOf(tasks);
            tasks.clear();
            copy.forEach(Runnable::run);
        }
    }

    private static final class RejectingExecutorService extends ManualExecutorService {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("full");
        }
    }
}
