package dev.ghidraex.viewstate;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs a synchronous listing adapter away from a host UI thread and publishes reducer-approved
 * snapshots through the host's native dispatcher.
 *
 * <p>The supplied worker executor must not execute tasks inline. The UI dispatcher may delay or
 * coalesce work: each callback reads the latest reducer snapshot when it runs, so an old queued
 * callback cannot rewind the UI. Completion outcomes run on the completing/submitting thread and
 * must not touch native UI; adapters use them for diagnostics and rejected-result lease cleanup.
 * This controller owns one registered view, not the reducer.</p>
 */
public final class AsyncListingController implements AutoCloseable {
    @FunctionalInterface
    public interface ListingLoader {
        ViewStateReducer.ListingWindow load(ViewStateReducer.ViewRequest request) throws Exception;
    }

    @FunctionalInterface
    public interface UiDispatcher {
        void dispatch(Runnable update);
    }

    public record CompletionOutcome(
            ViewStateReducer.ViewRequest request,
            Optional<ViewStateReducer.ListingWindow> result,
            ViewStateReducer.Acceptance acceptance,
            String detail) {
        public CompletionOutcome {
            Objects.requireNonNull(request, "request");
            result = Objects.requireNonNull(result, "result");
            Objects.requireNonNull(acceptance, "acceptance");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private final ViewStateReducer reducer;
    private final String viewId;
    private final ListingLoader loader;
    private final Executor worker;
    private final UiDispatcher uiDispatcher;
    private final Consumer<ViewStateReducer.ViewSnapshot> observer;
    private final Consumer<CompletionOutcome> outcomeObserver;
    private final AtomicBoolean closed = new AtomicBoolean();

    public AsyncListingController(
            ViewStateReducer reducer,
            String viewId,
            ListingLoader loader,
            Executor worker,
            UiDispatcher uiDispatcher,
            Consumer<ViewStateReducer.ViewSnapshot> observer) {
        this(reducer, viewId, loader, worker, uiDispatcher, observer, ignored -> {});
    }

    public AsyncListingController(
            ViewStateReducer reducer,
            String viewId,
            ListingLoader loader,
            Executor worker,
            UiDispatcher uiDispatcher,
            Consumer<ViewStateReducer.ViewSnapshot> observer,
            Consumer<CompletionOutcome> outcomeObserver) {
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.viewId = requireText(viewId, "viewId");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.outcomeObserver = Objects.requireNonNull(outcomeObserver, "outcomeObserver");
        if (reducer.view(viewId).isEmpty()) {
            throw new IllegalArgumentException("The reducer view must be registered first: " + viewId);
        }
    }

    public ViewStateReducer.ViewRequest request(ViewStateReducer.LocationRef anchor, int limit) {
        ensureOpen();
        ViewStateReducer.ViewRequest request = reducer.beginRequest(viewId, anchor, limit);
        try {
            publishLatest();
        } catch (RuntimeException dispatchFailure) {
            ViewStateReducer.Acceptance acceptance = reducer.fail(
                    viewId, request.requestId(), "UI dispatcher rejected the loading state");
            publishOutcome(request, Optional.empty(), acceptance, "UI dispatcher rejected loading state");
            throw dispatchFailure;
        }
        try {
            worker.execute(() -> load(request));
        } catch (RuntimeException error) {
            fail(request, error);
        }
        return request;
    }

    /**
     * Publishes the reducer's current state without starting provider work.
     *
     * <p>Hosts use this after an externally reduced event such as a content-generation advance or
     * event-sequence gap. The queued callback retains the same close/disposal checks and latest-state
     * read as request completion, so it cannot rewind or mutate a disposed view.</p>
     */
    public void publishCurrentState() {
        ensureOpen();
        publishLatest();
    }

    private void load(ViewStateReducer.ViewRequest request) {
        if (closed.get()) {
            ViewStateReducer.Acceptance acceptance = reducer.fail(
                    viewId, request.requestId(), "View disposed before provider entry");
            publishOutcome(request, Optional.empty(), acceptance, "Provider entry skipped after disposal");
            return;
        }
        ViewStateReducer.ListingWindow result;
        try {
            result = Objects.requireNonNull(loader.load(request), "Listing loader returned null");
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            fail(request, error);
            return;
        }
        ViewStateReducer.Acceptance acceptance = reducer.complete(result);
        publishOutcome(request, Optional.of(result), acceptance, "Provider result completed");
        if (acceptance.changesViewState()) {
            publishLatest();
        }
    }

    private void fail(ViewStateReducer.ViewRequest request, Exception error) {
        String message = error.getMessage();
        String detail = error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        ViewStateReducer.Acceptance acceptance = reducer.fail(viewId, request.requestId(), detail);
        publishOutcome(request, Optional.empty(), acceptance, detail);
        if (acceptance == ViewStateReducer.Acceptance.ACCEPTED) {
            publishLatest();
        }
    }

    private void publishOutcome(
            ViewStateReducer.ViewRequest request,
            Optional<ViewStateReducer.ListingWindow> result,
            ViewStateReducer.Acceptance acceptance,
            String detail) {
        outcomeObserver.accept(new CompletionOutcome(request, result, acceptance, detail));
    }

    private void publishLatest() {
        uiDispatcher.dispatch(() -> {
            if (closed.get() || reducer.disposed()) {
                return;
            }
            reducer.view(viewId).ifPresent(observer);
        });
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            reducer.disposeView(viewId);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Listing controller is closed: " + viewId);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
