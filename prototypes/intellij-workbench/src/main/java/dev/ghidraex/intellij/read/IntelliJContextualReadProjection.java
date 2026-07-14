package dev.ghidraex.intellij.read;

import dev.ghidraex.viewstate.AsyncContextualReadController;
import dev.ghidraex.viewstate.ContextualReadSlot;
import dev.ghidraex.viewstate.ViewStateReducer;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** IntelliJ lifecycle and EDT wrapper around one shared contextual read slot/controller pair. */
public final class IntelliJContextualReadProjection<Q, R> implements AutoCloseable {
    public interface UiAccess extends AsyncContextualReadController.UiDispatcher {
        boolean isDispatchThread();
    }

    private final ContextualReadSlot<Q, R> slot;
    private final AsyncContextualReadController<Q, R> controller;
    private final ExecutorService worker;
    private final UiAccess ui;
    private final AtomicBoolean closed = new AtomicBoolean();

    public IntelliJContextualReadProjection(
            String slotId,
            ViewStateReducer.ViewContext initialContext,
            UnaryOperator<R> copyAndValidate,
            Supplier<ViewStateReducer.ViewContext> contextSupplier,
            BooleanSupplier resyncRequired,
            AsyncContextualReadController.Loader<Q, R> loader,
            ExecutorService worker,
            UiAccess ui,
            Consumer<ContextualReadSlot.Snapshot<Q, R>> observer) {
        slot = new ContextualReadSlot<>(
                Objects.requireNonNull(slotId, "slotId"),
                Objects.requireNonNull(initialContext, "initialContext"),
                Objects.requireNonNull(copyAndValidate, "copyAndValidate"));
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ui = Objects.requireNonNull(ui, "ui");
        Consumer<ContextualReadSlot.Snapshot<Q, R>> acceptedObserver =
                Objects.requireNonNull(observer, "observer");
        controller = new AsyncContextualReadController<>(
                slot,
                Objects.requireNonNull(contextSupplier, "contextSupplier"),
                Objects.requireNonNull(resyncRequired, "resyncRequired"),
                Objects.requireNonNull(loader, "loader"),
                worker,
                ui,
                snapshot -> {
                    if (!ui.isDispatchThread()) {
                        throw new IllegalStateException("Contextual read escaped the IntelliJ EDT gateway");
                    }
                    acceptedObserver.accept(snapshot);
                });
    }

    public ContextualReadSlot.Request<Q> request(Q query) {
        requireUiThread();
        ensureOpen();
        return controller.request(Objects.requireNonNull(query, "query"));
    }

    public void contextChanged() {
        requireUiThread();
        ensureOpen();
        controller.contextChanged();
    }

    public ContextualReadSlot.Snapshot<Q, R> snapshot() {
        return slot.snapshot();
    }

    public boolean disposed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            controller.close();
            worker.shutdownNow();
        }
    }

    private void requireUiThread() {
        if (!ui.isDispatchThread()) {
            throw new IllegalStateException("IntelliJ contextual reads must be initiated on the EDT");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("IntelliJ contextual read projection is disposed");
        }
    }
}
