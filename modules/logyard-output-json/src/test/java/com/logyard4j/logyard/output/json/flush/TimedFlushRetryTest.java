package com.logyard4j.logyard.output.json.flush;

import com.logyard4j.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TimedFlushRetryTest {
    @Test
    void rejectedDispatchRetriesUntilTheOriginalRecordFlushesWithoutAnotherEvent() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectingDispatcher dispatcher = new RejectingDispatcher(3);
        List<Throwable> reported = new ArrayList<>();
        AtomicInteger flushes = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, reported::add, () -> {
                    flushes.incrementAndGet();
                    reference.get().flushCompleted();
                });
        reference.set(controller);

        controller.recordWritten();
        scheduler.runNext();
        controller.recordWritten();
        assertEquals(2, scheduler.scheduledCount());
        assertEquals(1, scheduler.pendingCount());

        scheduler.runNext();
        scheduler.runNext();
        scheduler.runNext();

        assertEquals(1, flushes.get());
        assertEquals(4, dispatcher.attempts.get());
        assertEquals(4, scheduler.scheduledCount());
        assertEquals(0, scheduler.pendingCount());
        assertEquals(3, reported.size());
        assertEquals(
                List.of(Duration.ofSeconds(1L), Duration.ofMillis(10L), Duration.ofMillis(50L), Duration.ofMillis(250L)),
                scheduler.scheduledDelays());

        dispatcher.rejectNext(1);
        controller.recordWritten();
        scheduler.runNext();
        assertEquals(Duration.ofMillis(10L), scheduler.scheduledDelays().get(5));
        scheduler.runNext();
        assertEquals(2, flushes.get());
        controller.close();
    }

    @Test
    void permanentDispatchRejectionBacksOffWithoutHotLoopOrDuplicateTasks() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectingDispatcher dispatcher = new RejectingDispatcher(Integer.MAX_VALUE);
        AtomicInteger flushes = new AtomicInteger();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, failure -> { }, flushes::incrementAndGet);

        controller.recordWritten();
        for (int attempt = 0; attempt < 6; attempt++) {
            scheduler.runNext();
            controller.recordWritten();
            assertEquals(1, scheduler.pendingCount());
            assertEquals(attempt + 2, scheduler.scheduledCount());
        }

        assertEquals(0, flushes.get());
        assertEquals(6, dispatcher.attempts.get());
        assertEquals(
                List.of(
                        Duration.ofSeconds(1L),
                        Duration.ofMillis(10L),
                        Duration.ofMillis(50L),
                        Duration.ofMillis(250L),
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L),
                        Duration.ofSeconds(1L)),
                scheduler.scheduledDelays());
        controller.close();
    }

    @Test
    void explicitFlushCancelsTheDispatchRetry() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectingDispatcher dispatcher = new RejectingDispatcher(Integer.MAX_VALUE);
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, failure -> { }, () -> { });

        controller.recordWritten();
        scheduler.runNext();
        controller.flushed();

        assertEquals(0, scheduler.pendingCount());
        assertThrows(IllegalStateException.class, scheduler::runNext);
        controller.close();
    }

    @Test
    void closeCancelsTheDispatchRetry() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectingDispatcher dispatcher = new RejectingDispatcher(Integer.MAX_VALUE);
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, failure -> { }, () -> { });

        controller.recordWritten();
        scheduler.runNext();
        assertEquals(1, scheduler.pendingCount());

        controller.close();

        assertEquals(0, scheduler.pendingCount());
        assertThrows(IllegalStateException.class, scheduler::runNext);
    }

    @Test
    void terminalTransportCallbackCancelsFurtherDispatchRetries() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectingDispatcher dispatcher = new RejectingDispatcher(1);
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, failure -> { }, () -> {
                    reference.get().cancelPending();
                    throw new IllegalStateException("terminal transport failure");
                });
        reference.set(controller);

        controller.recordWritten();
        scheduler.runNext();
        scheduler.runNext();

        assertEquals(0, scheduler.pendingCount());
        assertEquals(2, dispatcher.attempts.get());
        controller.close();
    }

    private static final class RejectingDispatcher implements FlushDispatcher {
        private final AtomicInteger remainingRejections;
        private final AtomicInteger attempts = new AtomicInteger();

        private RejectingDispatcher(int rejectedAttempts) {
            remainingRejections = new AtomicInteger(rejectedAttempts);
        }

        private void rejectNext(int rejectedAttempts) {
            remainingRejections.set(rejectedAttempts);
        }

        @Override
        public DispatchedFlush dispatch(Runnable action) {
            attempts.incrementAndGet();
            if (remainingRejections.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                throw new RejectedExecutionException("injected dispatch rejection");
            }
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
