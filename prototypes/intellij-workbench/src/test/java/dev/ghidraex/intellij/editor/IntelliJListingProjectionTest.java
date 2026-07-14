package dev.ghidraex.intellij.editor;

import dev.ghidraex.viewstate.AsyncListingController;
import dev.ghidraex.viewstate.ViewStateReducer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.CURRENT;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.EMPTY;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.RESYNCING;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.STALE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelliJListingProjectionTest {
    private final ViewStateReducer.ViewContext generation17 =
            new ViewStateReducer.ViewContext("runtime-local", 3, "orbit-controller", 17);
    private final ViewStateReducer.LocationRef entry = ListingSemantics.instructionLocation(0x401000);

    @Test
    void localTableGettersNeverExecuteTheListingSource() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var table = new SemanticListingTableModel();
        var sourceCalls = new AtomicInteger();
        var projection = projection(worker, ui, request -> {
            assertFalse(ui.isDispatchThread(), "source must run on the worker");
            sourceCalls.incrementAndGet();
            return window(request, request.context(), "mov-" + request.requestId());
        }, table::replace);

        ui.call(() -> projection.request(entry));
        assertEquals(0, sourceCalls.get());
        worker.runAt(0);
        ui.runAll();
        assertEquals(1, sourceCalls.get());
        assertEquals(1, table.getRowCount());
        assertEquals("mov-1", table.getValueAt(0, 3));

        ui.call(() -> projection.request(ListingSemantics.instructionLocation(0x401020)));
        int callsBeforeGetter = sourceCalls.get();
        assertEquals("mov-1", table.getValueAt(0, 3));
        assertEquals(callsBeforeGetter, sourceCalls.get(),
                "rendering a stale local row must not call the source");
        assertTrue(table.getRowCount() <= IntelliJListingProjection.WINDOW_LIMIT);
    }

    @Test
    void reversedCompletionsCannotReplaceTheLatestRequest() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var observed = new ArrayList<ViewStateReducer.ViewSnapshot>();
        var projection = projection(worker, ui,
                request -> window(request, request.context(), "request-" + request.requestId()),
                snapshot -> {
                    assertTrue(ui.isDispatchThread(), "native state must be observed on the EDT gateway");
                    observed.add(snapshot);
                });

        var a = ui.call(() -> projection.request(entry));
        var b = ui.call(() -> projection.request(ListingSemantics.instructionLocation(0x401004)));
        var c = ui.call(() -> projection.request(ListingSemantics.instructionLocation(0x401008)));
        ui.runAll();
        observed.clear();

        worker.runAt(1); // B
        ui.runAll();
        worker.runAt(0); // A
        ui.runAll();
        assertTrue(observed.isEmpty(), "superseded results must not reach native state");

        worker.runAt(0); // C
        ui.runAll();
        var displayed = projection.viewSnapshot().displayed();
        assertEquals(CURRENT, projection.viewSnapshot().freshness());
        assertEquals(c.requestId(), displayed.requestId());
        assertEquals("request-" + c.requestId(), displayed.rows().getFirst().fields().get("mnemonic"));
        assertNotEquals(a.requestId(), displayed.requestId());
        assertNotEquals(b.requestId(), displayed.requestId());
    }

    @Test
    void contextAndGenerationMismatchesLeaveTheCoherentWindowStale() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var wrongContext = new AtomicBoolean();
        var projection = projection(worker, ui, request -> {
            ViewStateReducer.ViewContext resultContext = wrongContext.get()
                    ? generation17
                    : request.context();
            return window(request, resultContext, "generation-" + resultContext.contentGeneration());
        }, snapshot -> assertTrue(ui.isDispatchThread()));

        ui.call(() -> projection.request(entry));
        worker.runAt(0);
        ui.runAll();
        String coherentResult = projection.viewSnapshot().displayed().resultId();

        ui.call(() -> projection.request(ListingSemantics.instructionLocation(0x401040)));
        ui.runAll();
        ui.run(() -> projection.acceptEvent(1, 18));
        worker.runAt(0);
        ui.runAll();

        assertEquals(18, projection.snapshot().context().contentGeneration());
        assertEquals(STALE, projection.viewSnapshot().freshness());
        assertEquals(coherentResult, projection.viewSnapshot().displayed().resultId());

        wrongContext.set(true);
        ui.call(() -> projection.request(ListingSemantics.instructionLocation(0x401080)));
        worker.runAt(0);
        ui.runAll();
        assertEquals(STALE, projection.viewSnapshot().freshness());
        assertEquals(coherentResult, projection.viewSnapshot().displayed().resultId());
        assertNull(projection.viewSnapshot().pending(),
                "a malformed unary completion must terminate its request");
        assertTrue(projection.viewSnapshot().detail().contains("mismatched"));
    }

    @Test
    void resyncQuarantineRejectsNavigationUntilAValidatedSnapshotArrives() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var projection = projection(worker, ui,
                request -> window(request, request.context(), "generation-"
                        + request.context().contentGeneration()),
                snapshot -> assertTrue(ui.isDispatchThread()));

        ui.call(() -> projection.request(entry));
        worker.runAt(0);
        ui.runAll();
        ui.run(() -> projection.acceptEvent(1, 17));
        ui.run(() -> projection.acceptEvent(3, 18));

        var quarantined = projection.viewSnapshot();
        assertEquals(RESYNCING, quarantined.freshness());
        assertTrue(projection.snapshot().resyncRequired());
        var blockedAnchor = ListingSemantics.instructionLocation(0x401080);
        assertThrows(IllegalStateException.class,
                () -> ui.call(() -> projection.request(blockedAnchor)));
        assertEquals(entry, projection.snapshot().location(),
                "a quarantined request must not mutate connected navigation");
        assertEquals(entry, projection.viewSnapshot().target(),
                "a quarantined request must not mutate the view target");
        assertEquals(0, worker.size(), "a quarantined request must not enqueue provider work");

        var generation18 = new ViewStateReducer.ViewContext(
                generation17.runtimeId(),
                generation17.runtimeEpoch(),
                generation17.programId(),
                18);
        ui.run(() -> projection.applySnapshot(generation18, 4));
        assertFalse(projection.snapshot().resyncRequired());
        assertEquals(STALE, projection.viewSnapshot().freshness());

        ui.call(() -> projection.request(blockedAnchor));
        worker.runAt(0);
        ui.runAll();
        assertEquals(CURRENT, projection.viewSnapshot().freshness());
        assertEquals(18, projection.viewSnapshot().displayed().context().contentGeneration());
    }

    @Test
    void validatedIdentitySwitchClearsTheNativeTableProjection() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var table = new SemanticListingTableModel();
        var projection = projection(worker, ui,
                request -> window(request, request.context(), "old-program"),
                table::replace);

        ui.call(() -> projection.request(entry));
        worker.runAt(0);
        ui.runAll();
        assertEquals(1, table.getRowCount());

        var replacement = new ViewStateReducer.ViewContext(
                "replacement-runtime", 1, "replacement-program", 0);
        ui.run(() -> projection.applySnapshot(replacement, 0));

        var cleared = projection.viewSnapshot();
        assertEquals(EMPTY, cleared.freshness());
        assertNull(cleared.target());
        assertNull(cleared.displayed());
        assertEquals(0, table.getRowCount(),
                "native rows must not retain the previous program after identity replacement");
        assertEquals(replacement, projection.snapshot().context());
    }

    @Test
    void disposalInvalidatesQueuedWorkAndLateUiApplication() {
        var worker = new ManualExecutor();
        var ui = new ManualUi();
        var sourceCalls = new AtomicInteger();
        var observed = new AtomicInteger();
        var projection = projection(worker, ui, request -> {
            sourceCalls.incrementAndGet();
            return window(request, request.context(), "late");
        }, snapshot -> observed.incrementAndGet());

        ui.call(() -> projection.request(entry));
        projection.close();
        worker.runAt(0);
        ui.runAll();
        assertEquals(0, sourceCalls.get(), "disposed queued work must not enter the source");
        assertEquals(0, observed.get(), "queued loading/completion callbacks must not touch disposed UI");
        assertTrue(projection.disposed());
        assertThrows(IllegalStateException.class, () -> ui.call(() -> projection.request(entry)));

        var secondWorker = new ManualExecutor();
        var secondUi = new ManualUi();
        var secondObserved = new AtomicInteger();
        var second = projection(secondWorker, secondUi,
                request -> window(request, request.context(), "completed-before-dispose"),
                snapshot -> secondObserved.incrementAndGet());
        secondUi.call(() -> second.request(entry));
        secondWorker.runAt(0); // accepted reducer result, UI callback is still queued
        second.close();
        secondUi.runAll();
        assertEquals(0, secondObserved.get(), "late queued UI application must be invalidated");
    }

    @Test
    void editorProjectionIdentityUsesRuntimeAndProgramAndStateUsesLocation() {
        var otherProgram = new ViewStateReducer.ViewContext("runtime-local", 3, "other-program", 17);
        var sameNameA = new VirtualizedListingFile("firmware.listing", generation17, entry);
        var sameNameB = new VirtualizedListingFile("firmware.listing", otherProgram, entry);
        var offcutRequested = ListingSemantics.memoryAddress(0x401003);
        var offcutContaining = ListingSemantics.memoryAddress(0x401000);
        var offcut = new ViewStateReducer.LocationRef(
                offcutRequested, offcutContaining, 3, "bytes");
        var state = new VirtualizedListingEditor.State(generation17, offcut);

        assertNotEquals(sameNameA.getPath(), sameNameB.getPath());
        assertTrue(sameNameA.identifies(generation17));
        assertFalse(sameNameA.identifies(otherProgram));
        assertEquals(offcut, state.location());
        assertTrue(state.location().offcut());
    }

    private IntelliJListingProjection projection(
            ManualExecutor worker,
            ManualUi ui,
            AsyncListingController.ListingLoader loader,
            java.util.function.Consumer<ViewStateReducer.ViewSnapshot> observer
    ) {
        return new IntelliJListingProjection(generation17, loader, worker, ui, observer);
    }

    private static ViewStateReducer.ListingWindow window(
            ViewStateReducer.ViewRequest request,
            ViewStateReducer.ViewContext resultContext,
            String mnemonic
    ) {
        var row = new ViewStateReducer.ListingRow(
                "row-" + request.requestId(),
                ViewStateReducer.RowKind.INSTRUCTION,
                request.anchor(),
                Map.of(
                        "address", request.anchor().requestedAddress().display(),
                        "bytes", "90",
                        "mnemonic", mnemonic,
                        "operands", ""
                )
        );
        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                resultContext,
                "result-" + request.requestId(),
                request.anchor(),
                List.of(row),
                ViewStateReducer.Completeness.COMPLETE,
                List.of()
        );
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAt(int index) {
            tasks.remove(index).run();
        }

        int size() {
            return tasks.size();
        }
    }

    private static final class ManualUi implements IntelliJListingProjection.UiAccess {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean dispatchThread;

        @Override
        public void dispatch(Runnable update) {
            tasks.addLast(update);
        }

        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        <T> T call(Supplier<T> operation) {
            if (dispatchThread) {
                return operation.get();
            }
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
                run(tasks.removeFirst());
            }
        }
    }
}
