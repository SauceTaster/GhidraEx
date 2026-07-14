package dev.ghidraex.intellij.read;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One running read plus at most one queued replacement for a single native view.
 *
 * <p>{@code AsyncContextualReadController} cancels its previous future before submitting the next
 * request. This executor removes those cancelled queued futures before admission, so a provider
 * that is slow to honor interruption cannot turn rapid navigation into an unbounded queue.</p>
 */
public final class LatestOnlyExecutorService extends ThreadPoolExecutor {
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private Future<?> latest;

    public LatestOnlyExecutorService(String viewId) {
        super(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                daemonThreads(viewId),
                new AbortPolicy()
        );
    }

    @Override
    public synchronized void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (latest != null) {
            latest.cancel(true);
        }
        getQueue().removeIf(task -> task instanceof Future<?> future && future.isCancelled());
        purge();
        Runnable admitted = command;
        if (command instanceof Future<?> future) {
            latest = future;
        } else {
            FutureTask<Void> future = new FutureTask<>(command, null);
            latest = future;
            admitted = future;
        }
        super.execute(admitted);
    }

    public int queuedTaskCount() {
        return getQueue().size();
    }

    @Override
    public synchronized java.util.List<Runnable> shutdownNow() {
        if (latest != null) {
            latest.cancel(true);
            latest = null;
        }
        return super.shutdownNow();
    }

    private static ThreadFactory daemonThreads(String viewId) {
        String safeViewId = Objects.requireNonNull(viewId, "viewId")
                .replaceAll("[^a-zA-Z0-9_.-]", "-");
        return task -> {
            Thread thread = new Thread(task,
                    "intellij-read-" + safeViewId + "-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
