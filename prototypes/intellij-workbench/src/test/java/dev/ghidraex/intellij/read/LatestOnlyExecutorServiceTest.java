package dev.ghidraex.intellij.read;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestOnlyExecutorServiceTest {
    @Test
    void interruptionResistantProviderStillAllowsOnlyOneQueuedReplacement() throws Exception {
        var worker = new LatestOnlyExecutorService("backpressure-test");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var newestRan = new CountDownLatch(1);
        try {
            worker.submit(() -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately model a provider that is slow to honor cancellation.
                    }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            Future<?> superseded = worker.submit(() -> { });
            worker.submit(newestRan::countDown);

            assertTrue(superseded.isCancelled());
            assertEquals(1, worker.queuedTaskCount());
            release.countDown();
            assertTrue(newestRan.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
