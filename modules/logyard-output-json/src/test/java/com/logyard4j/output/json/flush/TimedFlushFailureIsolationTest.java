package com.logyard4j.output.json.flush;

import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TimedFlushFailureIsolationTest {
    @Test
    void blockedStderrDoesNotDelayDispatchRetryOrEventualFlush() throws Exception {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        RejectOnceDispatcher dispatcher = new RejectOnceDispatcher();
        FlushDiagnostics.StderrFlushDiagnostics diagnostics = new FlushDiagnostics.StderrFlushDiagnostics();
        BlockingPrintStream blockedError = new BlockingPrintStream();
        PrintStream original = System.err;
        AtomicInteger flushes = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, diagnostics, () -> {
                    flushes.incrementAndGet();
                    reference.get().flushCompleted();
                });
        reference.set(controller);

        try {
            System.setErr(blockedError);
            controller.recordWritten();
            scheduler.runNext();
            assertTrue(blockedError.awaitBlocked());
            assertEquals(1, scheduler.pendingCount());

            scheduler.runNext();

            assertEquals(1, flushes.get());
            assertEquals(2, dispatcher.attempts.get());
            assertEquals(0, scheduler.pendingCount());
            assertTrue(diagnostics.reportIsInFlight());
        } finally {
            blockedError.release();
            await(() -> !diagnostics.reportIsInFlight());
            System.setErr(original);
            controller.close();
        }
    }

    @Test
    void fatalDispatchOnSharedSchedulerPreservesTheOriginalFlush() throws Exception {
        LinkageError fatal = new LinkageError("injected dispatcher failure");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicReference<String> workerName = new AtomicReference<>();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        CountDownLatch flushed = new CountDownLatch(1);
        BoundedElasticFlushDispatcher workers = new BoundedElasticFlushDispatcher(1, Duration.ofMillis(50L));
        FlushDispatcher dispatcher = action -> {
            if (attempts.incrementAndGet() == 1) {
                throw fatal;
            }
            return workers.dispatch(action);
        };
        TimedFlushController controller = new TimedFlushController(
                Duration.ofMillis(1L), FlushScheduler.shared(), dispatcher, failure -> diagnostics.incrementAndGet(), () -> {
                    workerName.set(Thread.currentThread().getName());
                    reference.get().flushCompleted();
                    flushed.countDown();
                });
        reference.set(controller);
        try {
            controller.recordWritten();

            assertTrue(flushed.await(2L, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(0, diagnostics.get());
            assertTrue(workerName.get().startsWith(BoundedElasticFlushDispatcher.WORKER_NAME_PREFIX));
        } finally {
            controller.close();
        }
    }

    @Test
    void fatalInitialSchedulingReleasesCandidateOwnership() {
        LinkageError fatal = new LinkageError("injected scheduling failure");
        ManualFlushScheduler scheduled = new ManualFlushScheduler();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        FlushScheduler scheduler = (delay, action) -> {
            if (attempts.incrementAndGet() == 1) {
                throw fatal;
            }
            return scheduled.schedule(delay, action);
        };
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, new ImmediateDispatcher(), failure -> { }, flushes::incrementAndGet);

        assertSame(fatal, assertThrows(LinkageError.class, controller::recordWritten));
        controller.recordWritten();
        assertEquals(1, scheduled.pendingCount());
        scheduled.runNext();
        assertEquals(1, flushes.get());
        controller.close();
    }

    @Test
    void recoverableRetrySchedulingFailureFlushesInlineWithoutAnotherRecord() {
        ManualFlushScheduler scheduled = new ManualFlushScheduler();
        AtomicInteger schedules = new AtomicInteger();
        FlushScheduler scheduler = (delay, action) -> {
            if (schedules.incrementAndGet() == 2) {
                throw new RejectedExecutionException("injected retry scheduling failure");
            }
            return scheduled.schedule(delay, action);
        };
        RejectOnceDispatcher dispatcher = new RejectOnceDispatcher();
        List<Throwable> diagnostics = new ArrayList<>();
        AtomicInteger flushes = new AtomicInteger();
        AtomicReference<TimedFlushController> reference = new AtomicReference<>();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, dispatcher, diagnostics::add, () -> {
                    flushes.incrementAndGet();
                    reference.get().flushCompleted();
                });
        reference.set(controller);

        controller.recordWritten();
        scheduled.runNext();

        assertEquals(1, flushes.get());
        assertEquals(1, dispatcher.attempts.get());
        assertEquals(2, schedules.get());
        assertEquals(2, diagnostics.size());
        assertEquals(0, scheduled.pendingCount());

        controller.recordWritten();
        assertEquals(3, schedules.get());
        assertEquals(1, scheduled.pendingCount());
        controller.close();
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

    private static final class RejectOnceDispatcher extends ImmediateDispatcher {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public DispatchedFlush dispatch(Runnable action) {
            if (attempts.incrementAndGet() == 1) {
                throw new RejectedExecutionException("injected dispatch rejection");
            }
            return super.dispatch(action);
        }
    }

    private static class ImmediateDispatcher implements FlushDispatcher {
        @Override
        public DispatchedFlush dispatch(Runnable action) {
            action.run();
            return new CompletedFlush();
        }
    }

    private static final class CompletedFlush implements FlushDispatcher.DispatchedFlush {
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
    }
}
