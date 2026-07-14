package dev.ghidraex.intellij.editor;

import dev.ghidraex.viewstate.AsyncListingController;
import dev.ghidraex.viewstate.ViewStateReducer;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** IntelliJ ownership wrapper around the shared asynchronous listing controller and reducer. */
public final class IntelliJListingProjection implements AutoCloseable {
    public static final String VIEW_ID = "listing";
    public static final int WINDOW_LIMIT = 512;

    public interface UiAccess extends AsyncListingController.UiDispatcher {
        boolean isDispatchThread();
    }

    private final ViewStateReducer reducer;
    private final AsyncListingController controller;
    private final UiAccess ui;
    private final Consumer<ViewStateReducer.ViewSnapshot> observer;
    private final Runnable closeWorker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public IntelliJListingProjection(
            ViewStateReducer.ViewContext context,
            AsyncListingController.ListingLoader loader,
            Executor worker,
            UiAccess ui,
            Consumer<ViewStateReducer.ViewSnapshot> observer
    ) {
        this(context, loader, worker, ui, observer, () -> { });
    }

    public IntelliJListingProjection(
            ViewStateReducer.ViewContext context,
            AsyncListingController.ListingLoader loader,
            Executor worker,
            UiAccess ui,
            Consumer<ViewStateReducer.ViewSnapshot> observer,
            Runnable closeWorker
    ) {
        this.reducer = new ViewStateReducer(Objects.requireNonNull(context, "context"));
        this.ui = Objects.requireNonNull(ui, "ui");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.closeWorker = Objects.requireNonNull(closeWorker, "closeWorker");
        AsyncListingController.ListingLoader listingLoader = Objects.requireNonNull(loader, "loader");
        reducer.registerView(VIEW_ID, true);
        this.controller = new AsyncListingController(
                reducer,
                VIEW_ID,
                request -> {
                    if (closed.get()) {
                        throw new CancellationException("Listing projection was disposed");
                    }
                    return listingLoader.load(request);
                },
                Objects.requireNonNull(worker, "worker"),
                ui,
                observer
        );
    }

    public ViewStateReducer.ViewRequest request(ViewStateReducer.LocationRef anchor) {
        requireUiThread();
        ensureOpen();
        if (!reducer.authoritativeActionsAllowed()) {
            throw new IllegalStateException(
                    "A validated full snapshot is required before navigating or requesting views");
        }
        reducer.navigate(Objects.requireNonNull(anchor, "anchor"));
        return controller.request(anchor, WINDOW_LIMIT);
    }

    public ViewStateReducer.EventAcceptance acceptEvent(long sequence, long contentGeneration) {
        requireUiThread();
        ensureOpen();
        ViewStateReducer.EventAcceptance acceptance = reducer.acceptEvent(sequence, contentGeneration);
        reducer.view(VIEW_ID).ifPresent(observer);
        return acceptance;
    }

    public void applySnapshot(ViewStateReducer.ViewContext context, long sequence) {
        requireUiThread();
        ensureOpen();
        reducer.applySnapshot(Objects.requireNonNull(context, "context"), sequence);
        reducer.view(VIEW_ID).ifPresent(observer);
    }

    public ViewStateReducer.WorkbenchSnapshot snapshot() {
        return reducer.snapshot();
    }

    public ViewStateReducer.ViewSnapshot viewSnapshot() {
        return reducer.view(VIEW_ID).orElseThrow();
    }

    public boolean disposed() {
        return closed.get();
    }

    private void requireUiThread() {
        if (!ui.isDispatchThread()) {
            throw new IllegalStateException("IntelliJ listing state must be initiated on the EDT");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("IntelliJ listing projection is disposed");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            controller.close();
            closeWorker.run();
        }
    }
}
