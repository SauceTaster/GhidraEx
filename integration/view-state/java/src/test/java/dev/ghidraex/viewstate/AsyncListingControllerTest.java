package dev.ghidraex.viewstate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ghidraex.viewstate.ViewStateReducer.Completeness.COMPLETE;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.CURRENT;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.FAILED;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.STALE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncListingControllerTest {
    private final ViewStateReducer.ViewContext context =
            new ViewStateReducer.ViewContext("runtime", 1, "program", 7);
    private final ViewStateReducer.AddressRef address =
            new ViewStateReducer.AddressRef("ram", 1, "1000", "ram:00001000");
    private final ViewStateReducer.LocationRef location =
            new ViewStateReducer.LocationRef(address, address, 0, "mnemonic");

    @Test
    void requestQueuesEngineWorkInsteadOfCallingTheLoader() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var calls = new AtomicInteger();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer,
                "listing",
                request -> {
                    calls.incrementAndGet();
                    return window(request);
                },
                worker,
                ui,
                ignored -> {});

        controller.request(location, 32);

        assertEquals(0, calls.get());
        assertEquals(ViewStateReducer.Freshness.LOADING,
                reducer.view("listing").orElseThrow().freshness());
        worker.runNext();
        assertEquals(1, calls.get());
        assertEquals(CURRENT, reducer.view("listing").orElseThrow().freshness());
    }

    @Test
    void reversedWorkerCompletionCannotReplaceTheLatestRequest() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ViewStateReducer.ViewSnapshot>();
        var outcomes = new ArrayList<AsyncListingController.CompletionOutcome>();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer, "listing", this::window, worker, ui, observed::add, outcomes::add);

        var a = controller.request(location, 32);
        var b = controller.request(location, 32);
        var c = controller.request(location, 32);
        worker.run(1);
        worker.run(0);
        worker.run(0);
        ui.runAll();

        assertEquals(c.requestId(), reducer.view("listing").orElseThrow().displayed().requestId());
        assertEquals(CURRENT, reducer.view("listing").orElseThrow().freshness());
        assertFalse(observed.isEmpty());
        assertTrue(observed.stream().allMatch(snapshot ->
                snapshot.displayed() == null || snapshot.displayed().requestId() == c.requestId()));
        assertTrue(a.requestId() < b.requestId() && b.requestId() < c.requestId());
        assertEquals(List.of(
                        ViewStateReducer.Acceptance.IGNORED_SUPERSEDED,
                        ViewStateReducer.Acceptance.IGNORED_SUPERSEDED,
                        ViewStateReducer.Acceptance.ACCEPTED),
                outcomes.stream().map(AsyncListingController.CompletionOutcome::acceptance).toList());
    }

    @Test
    void queuedUiCallbacksReadLatestStateAndCannotRewind() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ViewStateReducer.ViewSnapshot>();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer, "listing", this::window, worker, ui, observed::add);

        controller.request(location, 32);
        worker.runNext();
        controller.request(location, 32);
        ui.runAll();

        assertFalse(observed.isEmpty());
        assertTrue(observed.stream().allMatch(snapshot -> snapshot.freshness() == STALE));
    }

    @Test
    void externallyReducedEventCanPublishWithoutStartingAnotherLoad() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ViewStateReducer.ViewSnapshot>();
        var calls = new AtomicInteger();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer,
                "listing",
                request -> {
                    calls.incrementAndGet();
                    return window(request);
                },
                worker,
                ui,
                observed::add);
        controller.request(location, 32);
        worker.runNext();
        ui.runAll();
        observed.clear();

        reducer.acceptEvent(1, 8);
        controller.publishCurrentState();

        assertEquals(1, calls.get());
        assertEquals(0, worker.size());
        assertTrue(observed.isEmpty());
        assertEquals(1, ui.size());
        ui.runAll();
        assertEquals(1, observed.size());
        assertEquals(STALE, observed.getFirst().freshness());
    }

    @Test
    void loaderFailureBecomesExplicitFailedState() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer,
                "listing",
                request -> {
                    throw new IllegalStateException("fixture failure");
                },
                worker,
                ui,
                ignored -> {});

        controller.request(location, 32);
        worker.runNext();

        assertEquals(FAILED, reducer.view("listing").orElseThrow().freshness());
        assertTrue(reducer.view("listing").orElseThrow().detail().contains("fixture failure"));
    }

    @Test
    void malformedCurrentResultIsTerminalAndItsReasonIsPublished() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var observed = new ArrayList<ViewStateReducer.ViewSnapshot>();
        var outcomes = new ArrayList<AsyncListingController.CompletionOutcome>();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer,
                "listing",
                request -> new ViewStateReducer.ListingWindow(
                        request.viewId(),
                        request.requestId(),
                        new ViewStateReducer.ViewContext("runtime", 1, "program", 6),
                        "wrong-generation",
                        request.anchor(),
                        window(request).rows(),
                        COMPLETE,
                        List.of()),
                worker,
                ui,
                observed::add,
                outcomes::add);

        controller.request(location, 32);
        worker.runNext();
        ui.runAll();

        assertEquals(FAILED, reducer.view("listing").orElseThrow().freshness());
        assertTrue(reducer.view("listing").orElseThrow().pending() == null);
        assertEquals(ViewStateReducer.Acceptance.IGNORED_CONTEXT,
                outcomes.getFirst().acceptance());
        assertEquals(FAILED, observed.getLast().freshness());
    }

    @Test
    void closeSuppressesQueuedAndLateUiMutation() {
        var worker = new ManualExecutor();
        var ui = new ManualDispatcher();
        var observed = new AtomicInteger();
        var loaderCalls = new AtomicInteger();
        var outcomes = new ArrayList<AsyncListingController.CompletionOutcome>();
        var reducer = reducer();
        var controller = new AsyncListingController(
                reducer,
                "listing",
                request -> {
                    loaderCalls.incrementAndGet();
                    return window(request);
                },
                worker,
                ui,
                ignored -> observed.incrementAndGet(),
                outcomes::add);

        controller.request(location, 32);
        controller.close();
        worker.runNext();
        ui.runAll();

        assertTrue(controller.closed());
        assertEquals(0, loaderCalls.get());
        assertEquals(0, observed.get());
        assertEquals(ViewStateReducer.Acceptance.IGNORED_DISPOSED_VIEW,
                outcomes.getFirst().acceptance());
        assertThrows(IllegalStateException.class, () -> controller.request(location, 32));
    }

    private ViewStateReducer reducer() {
        var reducer = new ViewStateReducer(context);
        reducer.registerView("listing", true);
        reducer.navigate(location);
        return reducer;
    }

    private ViewStateReducer.ListingWindow window(ViewStateReducer.ViewRequest request) {
        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "result-" + request.requestId(),
                request.anchor(),
                List.of(new ViewStateReducer.ListingRow(
                        "row-" + request.requestId(),
                        ViewStateReducer.RowKind.INSTRUCTION,
                        request.anchor(),
                        Map.of("mnemonic", "mov"))),
                COMPLETE,
                List.of());
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            run(0);
        }

        private void run(int index) {
            tasks.remove(index).run();
        }

        private int size() {
            return tasks.size();
        }
    }

    private static final class ManualDispatcher implements AsyncListingController.UiDispatcher {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void dispatch(Runnable update) {
            tasks.add(update);
        }

        private void runAll() {
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }

        private int size() {
            return tasks.size();
        }
    }
}
