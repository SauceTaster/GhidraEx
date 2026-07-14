package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisJob;
import dev.ghidraex.engine.AnalysisProgress;
import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Starts and cancels analysis jobs without entering the engine from a JavaFX event handler. */
final class AsyncAnalysisLauncher implements AutoCloseable {
    enum State {
        STARTING,
        RUNNING,
        CURRENT,
        CANCELLED,
        FAILED;

        boolean terminal() {
            return this == CURRENT || this == CANCELLED || this == FAILED;
        }
    }

    record Snapshot(long runId, long sequence, State state, AnalysisProgress progress, String detail) {
        Snapshot {
            if (runId <= 0) {
                throw new IllegalArgumentException("runId must be positive");
            }
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            Objects.requireNonNull(state, "state");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    final class RunHandle {
        private final long runId;
        private final Consumer<Snapshot> observer;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private volatile AnalysisJob job;
        private volatile Future<?> launchTask;
        private volatile State state = State.STARTING;
        private volatile long publicationSequence;

        private RunHandle(long runId, Consumer<Snapshot> observer) {
            this.runId = runId;
            this.observer = observer;
        }

        void cancel() {
            if (!state.terminal() && cancelRequested.compareAndSet(false, true)) {
                Future<?> task = launchTask;
                AnalysisJob launched = job;
                if (launched == null && task != null && task.cancel(true)) {
                    publish(this, State.CANCELLED, null, "Analysis cancelled before provider entry");
                    return;
                }
                if (launched != null) {
                    try {
                        worker.submit(() -> cancelJob(this, launched));
                    } catch (RejectedExecutionException rejected) {
                        publish(this, State.FAILED, null, "Analysis cancellation worker is full");
                    }
                }
            }
        }

        boolean terminal() {
            return state.terminal();
        }
    }

    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final AnalysisEngine engine;
    private final ExecutorService worker;
    private final Consumer<Runnable> uiDispatcher;
    private final Runnable closeWorker;
    private final AtomicLong nextRunId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RunHandle active;

    static AsyncAnalysisLauncher createFx(AnalysisEngine engine) {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "fx-analysis-launch-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService worker = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                threads,
                new ThreadPoolExecutor.AbortPolicy());
        return new AsyncAnalysisLauncher(engine, worker, Platform::runLater, worker::shutdownNow);
    }

    AsyncAnalysisLauncher(
            AnalysisEngine engine,
            ExecutorService worker,
            Consumer<Runnable> uiDispatcher,
            Runnable closeWorker) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.closeWorker = Objects.requireNonNull(closeWorker, "closeWorker");
    }

    RunHandle start(Consumer<Snapshot> observer) {
        ensureOpen();
        RunHandle previous = active;
        if (previous != null && !previous.terminal()) {
            throw new IllegalStateException("An analysis launch is already active");
        }
        RunHandle run = new RunHandle(nextRunId.incrementAndGet(), Objects.requireNonNull(observer, "observer"));
        active = run;
        publish(run, State.STARTING, null, "Starting analysis on the provider worker");
        try {
            run.launchTask = worker.submit(() -> launch(run));
        } catch (RejectedExecutionException rejected) {
            publish(run, State.FAILED, null, "Analysis worker rejected the launch");
        }
        return run;
    }

    private void launch(RunHandle run) {
        if (!isAccepted(run)) {
            return;
        }
        try {
            AnalysisJob job = Objects.requireNonNull(
                    engine.startAnalysis(progress -> publish(run, State.RUNNING,
                            Objects.requireNonNull(progress, "progress"), progress.phase())),
                    "engine.startAnalysis()");
            run.job = job;
            if (run.cancelRequested.get()) {
                cancelJob(run, job);
                return;
            }
            job.completion().whenComplete((unused, failure) -> {
                if (run.cancelRequested.get() || job.isCancelled()) {
                    publish(run, State.CANCELLED, null, "Analysis cancelled");
                } else if (failure != null) {
                    publish(run, State.FAILED, null, failureDetail(failure));
                } else {
                    publish(run, State.CURRENT, null, "Analysis current");
                }
            });
        } catch (RuntimeException failure) {
            publish(run, State.FAILED, null, failureDetail(failure));
        }
    }

    private void cancelJob(RunHandle run, AnalysisJob job) {
        try {
            job.cancel();
            publish(run, State.CANCELLED, null, "Analysis cancelled");
        } catch (RuntimeException failure) {
            publish(run, State.FAILED, null, failureDetail(failure));
        }
    }

    private void publish(
            RunHandle run,
            State nextState,
            AnalysisProgress progress,
            String detail) {
        if (!isAccepted(run)) {
            return;
        }
        Snapshot snapshot;
        synchronized (run) {
            if (run.state.terminal()) {
                return;
            }
            run.state = nextState;
            snapshot = new Snapshot(run.runId, ++run.publicationSequence, nextState, progress, detail);
        }
        uiDispatcher.accept(() -> {
            if (isAccepted(run) && run.publicationSequence == snapshot.sequence()) {
                run.observer.accept(snapshot);
            }
        });
    }

    private boolean isAccepted(RunHandle run) {
        return !closed.get() && active == run;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            RunHandle run = active;
            if (run != null) {
                Future<?> task = run.launchTask;
                if (task != null) {
                    task.cancel(true);
                }
                AnalysisJob job = run.job;
                if (job != null) {
                    job.cancel();
                }
            }
            closeWorker.run();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Analysis launcher is closed");
        }
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
