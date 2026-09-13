package com.logyard4j.logyard.output.json.flush;

import com.logyard4j.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TimedFlushConcurrencyTest {
    @Test
    void twoBlockedFlushesDoNotStarveAThirdDeadline() throws Exception {
        CountDownLatch blocked = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fast = new CountDownLatch(1);
        TimedFlushController first = new TimedFlushController(Duration.ofMillis(1L), () -> block(blocked, release));
        TimedFlushController second = new TimedFlushController(Duration.ofMillis(1L), () -> block(blocked, release));
        TimedFlushController third = new TimedFlushController(Duration.ofMillis(1L), fast::countDown);
        try {
            first.recordWritten();
            second.recordWritten();
            assertTrue(blocked.await(2L, TimeUnit.SECONDS), "two platform flush workers did not block");

            third.recordWritten();
            assertTrue(fast.await(2L, TimeUnit.SECONDS), "blocked transports starved an independent deadline");
        } finally {
            release.countDown();
            first.close();
            second.close();
            third.close();
        }
    }

    @Test
    void oneSinkNeverOwnsConcurrentTimedFlushes() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(Duration.ofSeconds(1L), scheduler, () -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            awaitUninterruptibly(release);
            active.decrementAndGet();
            reference.get().flushCompleted();
            completed.countDown();
        });
        reference.set(controller);

        controller.recordWritten();
        scheduler.runNext();
        assertTrue(entered.await(2L, TimeUnit.SECONDS));
        for (int index = 0; index < 100; index++) {
            controller.recordWritten();
        }
        assertEquals(1, scheduler.scheduledCount());

        release.countDown();
        assertTrue(completed.await(2L, TimeUnit.SECONDS));
        controller.recordWritten();
        await(() -> scheduler.scheduledCount() == 2);
        assertEquals(1, maximum.get());
        controller.close();
    }

    @Test
    void explicitFlushCancelsADispatchedTaskBeforeTransportIo() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountDownLatch dispatched = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger transportFlushes = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(Duration.ofSeconds(1L), scheduler, () -> {
            dispatched.countDown();
            awaitUninterruptibly(proceed);
            if (reference.get().flushIsCurrent()) {
                transportFlushes.incrementAndGet();
                reference.get().flushCompleted();
            }
        });
        reference.set(controller);

        controller.recordWritten();
        scheduler.runNext();
        assertTrue(dispatched.await(2L, TimeUnit.SECONDS));
        controller.flushed();
        proceed.countDown();
        controller.close();

        assertEquals(0, transportFlushes.get());
    }

    @Test
    void closeWaitsForADispatchedTaskToReleaseOwnership() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        CountDownLatch dispatched = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger transportFlushes = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(Duration.ofSeconds(1L), scheduler, () -> {
            dispatched.countDown();
            try {
                release.await();
            } catch (InterruptedException interruption) {
                canceled.countDown();
                awaitUninterruptibly(release);
                Thread.currentThread().interrupt();
            }
            if (reference.get().flushIsCurrent()) {
                transportFlushes.incrementAndGet();
            }
        });
        reference.set(controller);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            controller.recordWritten();
            scheduler.runNext();
            assertTrue(dispatched.await(2L, TimeUnit.SECONDS));

            Future<?> closed = closer.submit(controller::close);
            assertTrue(canceled.await(2L, TimeUnit.SECONDS));
            release.countDown();
            closed.get(2L, TimeUnit.SECONDS);
            controller.recordWritten();

            assertEquals(0, transportFlushes.get());
            assertEquals(1, scheduler.scheduledCount());
            assertEquals(0, scheduler.pendingCount());
        } finally {
            release.countDown();
            closer.shutdownNow();
            controller.close();
        }
    }

    @Test
    void closePreventsSchedulingFailureFallbackFromRunning() throws Exception {
        CountDownLatch scheduling = new CountDownLatch(1);
        CountDownLatch failScheduling = new CountDownLatch(1);
        AtomicInteger transportFlushes = new AtomicInteger();
        FlushScheduler failingScheduler = (delay, action) -> {
            scheduling.countDown();
            awaitUninterruptibly(failScheduling);
            throw new IllegalStateException("scheduler unavailable");
        };
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), failingScheduler, new ImmediateDispatcher(), failure -> { }, transportFlushes::incrementAndGet);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            Future<?> recordWritten = producer.submit(controller::recordWritten);
            assertTrue(scheduling.await(2L, TimeUnit.SECONDS));

            controller.close();
            failScheduling.countDown();
            recordWritten.get(2L, TimeUnit.SECONDS);

            assertEquals(0, transportFlushes.get());
        } finally {
            failScheduling.countDown();
            producer.shutdownNow();
            controller.close();
        }
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        awaitUninterruptibly(release);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied before the deadline");
            }
            Thread.onSpinWait();
        }
    }

    private static final class ImmediateDispatcher implements FlushDispatcher {
        @Override
        public DispatchedFlush dispatch(Runnable action) {
            action.run();
            return new DispatchedFlush() {
                @Override
                public void cancel() {
                }

                @Override
                public void awaitCompletion() {
                }

                @Override
                public boolean completed() {
                    return true;
                }
            };
        }
    }
}
