package dev.ghidraex.intellij.read;

import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.viewstate.ContextualReadSlot;
import dev.ghidraex.viewstate.ViewStateReducer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelliJContextualReadProjectionTest {
    private final ViewStateReducer.ViewContext generation7 =
            new ViewStateReducer.ViewContext("runtime", 1, "program", 7);

    @Test
    void reversedReadsCannotRewindNativeStateAndOnlyLatestProviderEnters() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var calls = new ArrayList<String>();
        var observed = new ArrayList<ContextualReadSlot.Snapshot<String, List<String>>>();
        var projection = projection(context, new AtomicBoolean(), worker, ui, request -> {
            calls.add(request.query());
            return loaded(request, List.of(request.query()), ContextualReadSlot.Completeness.COMPLETE);
        }, observed::add);

        ui.call(() -> projection.request("old"));
        ui.call(() -> projection.request("new"));
        worker.runAll();
        ui.runAll();

        assertEquals(List.of("new"), calls);
        assertEquals(List.of("new"), projection.snapshot().displayed().value());
        assertEquals(ContextualReadSlot.Freshness.CURRENT, projection.snapshot().freshness());
        assertTrue(observed.stream().allMatch(ignored -> ui.lastCallbackRanOnUi));
    }

    @Test
    void providerFailureAndPartialResultAreExplicitNativeStates() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var fail = new AtomicBoolean(true);
        var projection = projection(context, new AtomicBoolean(), worker, ui, request -> {
            if (fail.get()) {
                throw new IllegalStateException("decompiler unavailable");
            }
            return loaded(request, List.of("bounded"), ContextualReadSlot.Completeness.TRUNCATED);
        }, ignored -> { });

        ui.call(() -> projection.request("decode"));
        worker.runAll();
        ui.runAll();
        assertEquals(ContextualReadSlot.Freshness.FAILED, projection.snapshot().freshness());
        assertTrue(projection.snapshot().detail().contains("decompiler unavailable"));

        fail.set(false);
        ui.call(() -> projection.request("decode"));
        worker.runAll();
        ui.runAll();
        assertEquals(ContextualReadSlot.Freshness.PARTIAL, projection.snapshot().freshness());
    }

    @Test
    void identityReplacementAndResyncCancelWorkAndClearOrQuarantineState() {
        var context = new AtomicReference<>(generation7);
        var resync = new AtomicBoolean();
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var calls = new AtomicInteger();
        var projection = projection(context, resync, worker, ui, request -> {
            calls.incrementAndGet();
            return loaded(request, List.of(request.query()), ContextualReadSlot.Completeness.COMPLETE);
        }, ignored -> { });

        ui.call(() -> projection.request("current"));
        worker.runAll();
        ui.runAll();
        ui.call(() -> projection.request("late"));
        context.set(new ViewStateReducer.ViewContext("runtime", 2, "replacement", 0));
        ui.run(projection::contextChanged);
        worker.runAll();
        ui.runAll();

        assertEquals(1, calls.get(), "identity replacement must cancel queued provider entry");
        assertEquals(ContextualReadSlot.Freshness.EMPTY, projection.snapshot().freshness());
        assertNull(projection.snapshot().displayed());

        resync.set(true);
        ui.run(projection::contextChanged);
        ui.runAll();
        assertEquals(ContextualReadSlot.Freshness.RESYNCING, projection.snapshot().freshness());
        assertThrows(IllegalStateException.class,
                () -> ui.call(() -> projection.request("blocked")));
    }

    @Test
    void providerMustEchoExactContextAndQuery() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var wrong = new ViewStateReducer.ViewContext("runtime", 1, "program", 8);
        var projection = projection(context, new AtomicBoolean(), worker, ui, request ->
                new ContextualReadSlot.LoadedResult<>(
                        wrong,
                        "different-query",
                        "wrong-envelope",
                        List.of("unsafe"),
                        ContextualReadSlot.Completeness.COMPLETE,
                        List.of()), ignored -> { });

        ui.call(() -> projection.request("decode"));
        worker.runAll();
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, projection.snapshot().freshness());
        assertNull(projection.snapshot().pending());
        assertNull(projection.snapshot().displayed());
        assertTrue(projection.snapshot().detail().contains("mismatched"));
    }

    @Test
    void disposalSuppressesQueuedProviderAndUiApplication() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var calls = new AtomicInteger();
        var observed = new AtomicInteger();
        var projection = projection(context, new AtomicBoolean(), worker, ui, request -> {
            calls.incrementAndGet();
            return loaded(request, List.of("late"), ContextualReadSlot.Completeness.COMPLETE);
        }, ignored -> observed.incrementAndGet());

        ui.call(() -> projection.request("decode"));
        projection.close();
        worker.runAll();
        ui.runAll();

        assertEquals(0, calls.get());
        assertEquals(0, observed.get());
        assertTrue(projection.snapshot().disposed());
        assertThrows(IllegalStateException.class,
                () -> ui.call(() -> projection.request("again")));
    }

    @Test
    void oversizeProviderDescriptorFailsWithoutEnteringANativeModel() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var projection = new IntelliJContextualReadProjection<
                ReadModels.ProgramQuery, ReadModels.ProgramCatalog>(
                "programs",
                generation7,
                value -> new ReadModels.ProgramCatalog(value.programs()),
                context::get,
                () -> false,
                request -> new ContextualReadSlot.LoadedResult<>(
                        request.context(),
                        request.query(),
                        "oversize",
                        new ReadModels.ProgramCatalog(List.of(new ProgramDescriptor(
                                "p",
                                "x".repeat(ReadModels.MAX_DESCRIPTOR_FIELD_CHARS + 1),
                                "x86",
                                0,
                                1))),
                        ContextualReadSlot.Completeness.COMPLETE,
                        List.of()),
                worker,
                ui,
                ignored -> { });

        ui.call(() -> projection.request(new ReadModels.ProgramQuery(4)));
        worker.runAll();
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, projection.snapshot().freshness());
        assertNull(projection.snapshot().displayed());
        assertTrue(projection.snapshot().detail().contains("4096"));
    }

    @Test
    void oversizeRuntimeProviderPayloadFailsBeforeNativeApplication() {
        var context = new AtomicReference<>(generation7);
        var worker = new ManualExecutorService();
        var ui = new ManualUi();
        var projection = new IntelliJContextualReadProjection<
                ReadModels.RuntimeQuery, ReadModels.RuntimeDetails>(
                "runtime",
                generation7,
                value -> new ReadModels.RuntimeDetails(
                        value.backend(), value.capabilities(), value.integrations()),
                context::get,
                () -> false,
                request -> new ContextualReadSlot.LoadedResult<>(
                        request.context(),
                        request.query(),
                        "oversize-runtime",
                        new ReadModels.RuntimeDetails(
                                new dev.ghidraex.backend.BackendProbe.Status(
                                        dev.ghidraex.backend.BackendProbe.DiscoveryState.NOT_CONFIGURED,
                                        "SyntheticAnalysisEngine",
                                        "none",
                                        null,
                                        null,
                                        "pending",
                                        "x".repeat(ReadModels.MAX_RUNTIME_FIELD_CHARS + 1)),
                                List.of(),
                                List.of()),
                        ContextualReadSlot.Completeness.COMPLETE,
                        List.of()),
                worker,
                ui,
                ignored -> { });

        ui.call(() -> projection.request(new ReadModels.RuntimeQuery(java.nio.file.Path.of("."))));
        worker.runAll();
        ui.runAll();

        assertEquals(ContextualReadSlot.Freshness.FAILED, projection.snapshot().freshness());
        assertNull(projection.snapshot().displayed());
        assertTrue(projection.snapshot().detail().contains("16384"));
    }

    @Test
    void requestsMustOriginateOnTheEdtGateway() {
        var projection = projection(
                new AtomicReference<>(generation7),
                new AtomicBoolean(),
                new ManualExecutorService(),
                new ManualUi(),
                request -> loaded(request, List.of("unused"), ContextualReadSlot.Completeness.COMPLETE),
                ignored -> { });

        assertThrows(IllegalStateException.class, () -> projection.request("wrong-thread"));
    }

    private IntelliJContextualReadProjection<String, List<String>> projection(
            AtomicReference<ViewStateReducer.ViewContext> context,
            AtomicBoolean resync,
            ManualExecutorService worker,
            ManualUi ui,
            dev.ghidraex.viewstate.AsyncContextualReadController.Loader<String, List<String>> loader,
            java.util.function.Consumer<ContextualReadSlot.Snapshot<String, List<String>>> observer) {
        return new IntelliJContextualReadProjection<>(
                "read",
                context.get(),
                List::copyOf,
                context::get,
                resync::get,
                loader,
                worker,
                ui,
                observer);
    }

    private <Q> ContextualReadSlot.LoadedResult<Q, List<String>> loaded(
            ContextualReadSlot.Request<Q> request,
            List<String> value,
            ContextualReadSlot.Completeness completeness) {
        return new ContextualReadSlot.LoadedResult<>(
                request.context(), request.query(), "result-" + request.requestId(), value,
                completeness, completeness == ContextualReadSlot.Completeness.COMPLETE
                ? List.of() : List.of("bounded output"));
    }

    private static final class ManualUi implements IntelliJContextualReadProjection.UiAccess {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean dispatchThread;
        private boolean lastCallbackRanOnUi;

        @Override
        public void dispatch(Runnable update) {
            tasks.addLast(update);
        }

        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        <T> T call(Supplier<T> operation) {
            dispatchThread = true;
            try {
                return operation.get();
            } finally {
                dispatchThread = false;
            }
        }

        void run(Runnable operation) {
            call(() -> {
                operation.run();
                return null;
            });
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                run(() -> {
                    lastCallbackRanOnUi = isDispatchThread();
                    tasks.removeFirst().run();
                });
            }
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
            if (shutdown) {
                throw new RejectedExecutionException("shutdown");
            }
            tasks.add(command);
        }

        void runAll() {
            List<Runnable> copy = List.copyOf(tasks);
            tasks.clear();
            copy.forEach(Runnable::run);
        }
    }
}
