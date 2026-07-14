package dev.ghidraex.fx;

import dev.ghidraex.viewstate.ViewStateReducer;
import dev.ghidraex.viewstate.AsyncListingController;
import dev.ghidraex.viewstate.ViewStateReducer.EventAcceptance;
import dev.ghidraex.viewstate.ViewStateReducer.LocationRef;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;
import dev.ghidraex.viewstate.ViewStateReducer.ViewRequest;
import dev.ghidraex.viewstate.ViewStateReducer.ViewSnapshot;
import dev.ghidraex.viewstate.ViewStateReducer.WorkbenchSnapshot;
import javafx.application.Platform;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * JavaFX host adapter for one bounded semantic listing view.
 *
 * <p>Provider calls always run on the supplied worker. Reducer acceptance happens before a UI
 * dispatch, and every dispatched callback reads the latest accepted reducer state. This prevents a
 * delayed response or an already-queued callback from repainting a superseded or disposed view.</p>
 */
final class AsyncListingProjection implements AutoCloseable {
    static final String VIEW_ID = "listing";

    @FunctionalInterface
    interface Provider extends AsyncListingController.ListingLoader {
    }

    @FunctionalInterface
    interface UiDispatcher {
        void dispatch(Runnable action);
    }

    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();
    private static final int PROVIDER_THREADS = 2;
    private static final int MAX_QUEUED_REQUESTS = 8;

    private final ViewStateReducer reducer;
    private final AsyncListingController controller;
    private final Runnable closeWorker;
    private final AtomicBoolean closed = new AtomicBoolean();

    static AsyncListingProjection createFx(
            ViewContext context,
            Provider provider,
            Consumer<ViewSnapshot> observer) {
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "fx-listing-provider-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService workers = new ThreadPoolExecutor(
                PROVIDER_THREADS,
                PROVIDER_THREADS,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_REQUESTS),
                threads,
                new ThreadPoolExecutor.AbortPolicy());
        return new AsyncListingProjection(
                context, provider, workers, Platform::runLater, observer, workers::shutdownNow);
    }

    AsyncListingProjection(
            ViewContext context,
            Provider provider,
            Executor worker,
            UiDispatcher uiDispatcher,
            Consumer<ViewSnapshot> observer) {
        this(context, provider, worker, uiDispatcher, observer, () -> { });
    }

    AsyncListingProjection(
            ViewContext context,
            Provider provider,
            Executor worker,
            UiDispatcher uiDispatcher,
            Consumer<ViewSnapshot> observer,
            Runnable closeWorker) {
        this.closeWorker = Objects.requireNonNull(closeWorker, "closeWorker");
        reducer = new ViewStateReducer(Objects.requireNonNull(context, "context"));
        reducer.registerView(VIEW_ID, true);
        controller = new AsyncListingController(
                reducer,
                VIEW_ID,
                Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(worker, "worker"),
                Objects.requireNonNull(uiDispatcher, "uiDispatcher")::dispatch,
                Objects.requireNonNull(observer, "observer"));
    }

    ViewRequest request(LocationRef anchor, int limit) {
        ensureOpen();
        reducer.navigate(Objects.requireNonNull(anchor, "anchor"));
        return controller.request(anchor, limit);
    }

    void setSelection(LocationRef selected) {
        ensureOpen();
        reducer.setSelection(selected == null ? Set.of() : Set.of(selected));
    }

    EventAcceptance acceptContentEvent(long sequence, long contentGeneration) {
        ensureOpen();
        EventAcceptance acceptance = reducer.acceptEvent(sequence, contentGeneration);
        controller.publishCurrentState();
        return acceptance;
    }

    void applyValidatedSnapshot(ViewContext context, long sequence) {
        ensureOpen();
        reducer.applySnapshot(Objects.requireNonNull(context, "context"), sequence);
        controller.publishCurrentState();
    }

    WorkbenchSnapshot snapshot() {
        return reducer.snapshot();
    }

    ViewSnapshot viewSnapshot() {
        return reducer.view(VIEW_ID).orElseThrow();
    }

    boolean disposed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            controller.close();
            reducer.dispose();
            closeWorker.run();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Listing projection is disposed");
        }
    }
}
