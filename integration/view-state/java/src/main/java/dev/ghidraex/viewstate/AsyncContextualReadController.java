package dev.ghidraex.viewstate;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Executes one contextual read at a time and publishes only reducer-approved snapshots. */
public final class AsyncContextualReadController<Q, R> implements AutoCloseable {
    @FunctionalInterface
    public interface Loader<Q, R> {
        ContextualReadSlot.LoadedResult<Q, R> load(ContextualReadSlot.Request<Q> request) throws Exception;
    }

    @FunctionalInterface
    public interface UiDispatcher {
        void dispatch(Runnable update);
    }

    public record Outcome<Q, R>(
            ContextualReadSlot.Request<Q> request,
            Optional<ContextualReadSlot.LoadedResult<Q, R>> result,
            ContextualReadSlot.Acceptance acceptance,
            String detail) {
        public Outcome {
            Objects.requireNonNull(request, "request");
            result = Objects.requireNonNull(result, "result");
            Objects.requireNonNull(acceptance, "acceptance");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    private final ContextualReadSlot<Q, R> slot;
    private final Supplier<ViewStateReducer.ViewContext> contextSupplier;
    private final BooleanSupplier resyncRequired;
    private final Loader<Q, R> loader;
    private final ExecutorService worker;
    private final UiDispatcher uiDispatcher;
    private final Consumer<ContextualReadSlot.Snapshot<Q, R>> observer;
    private final Consumer<Outcome<Q, R>> outcomeObserver;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object taskLock = new Object();
    private Future<?> activeTask;

    public AsyncContextualReadController(
            ContextualReadSlot<Q, R> slot,
            Supplier<ViewStateReducer.ViewContext> contextSupplier,
            BooleanSupplier resyncRequired,
            Loader<Q, R> loader,
            ExecutorService worker,
            UiDispatcher uiDispatcher,
            Consumer<ContextualReadSlot.Snapshot<Q, R>> observer) {
        this(slot, contextSupplier, resyncRequired, loader, worker, uiDispatcher, observer, ignored -> {});
    }

    public AsyncContextualReadController(
            ContextualReadSlot<Q, R> slot,
            Supplier<ViewStateReducer.ViewContext> contextSupplier,
            BooleanSupplier resyncRequired,
            Loader<Q, R> loader,
            ExecutorService worker,
            UiDispatcher uiDispatcher,
            Consumer<ContextualReadSlot.Snapshot<Q, R>> observer,
            Consumer<Outcome<Q, R>> outcomeObserver) {
        this.slot = Objects.requireNonNull(slot, "slot");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.resyncRequired = Objects.requireNonNull(resyncRequired, "resyncRequired");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.outcomeObserver = Objects.requireNonNull(outcomeObserver, "outcomeObserver");
    }

    public ContextualReadSlot.Request<Q> request(Q query) {
        ensureOpen();
        ContextualReadSlot.Request<Q> request = slot.begin(
                contextSupplier.get(), query, resyncRequired.getAsBoolean());
        try {
            publishLatest();
        } catch (RuntimeException dispatchFailure) {
            ContextualReadSlot.Acceptance acceptance = slot.fail(
                    request.requestId(),
                    "UI dispatcher rejected the loading state",
                    contextSupplier.get(),
                    resyncRequired.getAsBoolean());
            cancelActive();
            publishOutcome(request, Optional.empty(), acceptance, "UI dispatcher rejected loading state");
            throw dispatchFailure;
        }
        synchronized (taskLock) {
            if (activeTask != null) {
                activeTask.cancel(true);
            }
            try {
                activeTask = worker.submit(() -> load(request));
            } catch (RejectedExecutionException rejected) {
                ContextualReadSlot.Acceptance acceptance = slot.fail(
                        request.requestId(),
                        "Read worker rejected bounded work",
                        contextSupplier.get(),
                        resyncRequired.getAsBoolean());
                publishOutcome(request, Optional.empty(), acceptance, "Worker queue is full");
                if (acceptance.changesState()) {
                    publishLatest();
                }
            }
        }
        return request;
    }

    /** Reconciles a validated event/snapshot transition and cancels obsolete provider work. */
    public void contextChanged() {
        ensureOpen();
        slot.contextChanged(contextSupplier.get(), resyncRequired.getAsBoolean());
        cancelActive();
        publishLatest();
    }

    private void load(ContextualReadSlot.Request<Q> request) {
        if (closed.get()) {
            publishOutcome(request, Optional.empty(), ContextualReadSlot.Acceptance.IGNORED_DISPOSED,
                    "Provider entry skipped after disposal");
            return;
        }
        if (Thread.currentThread().isInterrupted() || !slot.isPending(request)) {
            ContextualReadSlot.Acceptance acceptance = slot.fail(
                    request.requestId(),
                    "Read superseded before provider entry",
                    contextSupplier.get(),
                    resyncRequired.getAsBoolean());
            publishOutcome(request, Optional.empty(), acceptance,
                    "Provider entry skipped after cancellation or supersession");
            return;
        }
        ContextualReadSlot.LoadedResult<Q, R> loaded;
        try {
            loaded = Objects.requireNonNull(loader.load(request), "Read loader returned null");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ContextualReadSlot.Acceptance acceptance = slot.fail(
                    request.requestId(),
                    failureDetail(failure),
                    contextSupplier.get(),
                    resyncRequired.getAsBoolean());
            publishOutcome(request, Optional.empty(), acceptance, failureDetail(failure));
            if (acceptance.changesState()) {
                publishLatest();
            }
            return;
        }
        ContextualReadSlot.Acceptance acceptance = slot.complete(
                request, loaded, contextSupplier.get(), resyncRequired.getAsBoolean());
        try {
            publishOutcome(request, Optional.of(loaded), acceptance, "Provider result completed");
        } finally {
            if (acceptance.changesState()) {
                publishLatest();
            }
        }
    }

    private void publishOutcome(
            ContextualReadSlot.Request<Q> request,
            Optional<ContextualReadSlot.LoadedResult<Q, R>> result,
            ContextualReadSlot.Acceptance acceptance,
            String detail) {
        try {
            outcomeObserver.accept(new Outcome<>(request, result, acceptance, detail));
        } catch (RuntimeException ignored) {
            // Outcome observers are best-effort telemetry/resource-release hooks. A faulty hook
            // must not suppress the authoritative slot transition or the corresponding UI state.
        }
    }

    private void publishLatest() {
        uiDispatcher.dispatch(() -> {
            if (!closed.get()) {
                observer.accept(slot.snapshot());
            }
        });
    }

    private void cancelActive() {
        synchronized (taskLock) {
            if (activeTask != null) {
                activeTask.cancel(true);
                activeTask = null;
            }
        }
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelActive();
            slot.dispose();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Contextual read controller is closed");
        }
    }

    private static String failureDetail(Exception failure) {
        String message = failure.getMessage();
        String detail = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return detail.length() <= 4_096 ? detail : detail.substring(0, 4_096);
    }
}
