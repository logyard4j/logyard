package com.zsumz.logyard.output.json.flush;

import com.zsumz.logyard.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TimedFlushControllerTest {
    @Test
    void oneTaskCoversEveryRecordUntilTheOutputIsFlushed() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        AtomicInteger flushes = new AtomicInteger();
        TimedFlushController controller = controller(Duration.ofSeconds(1L), scheduler, flushes::incrementAndGet);

        controller.recordWritten();
        controller.recordWritten();
        controller.recordWritten();

        assertEquals(1, scheduler.scheduledCount());
        assertEquals(1, scheduler.pendingCount());
        scheduler.runNext();
        assertEquals(1, flushes.get());
        assertEquals(0, scheduler.pendingCount());

        controller.recordWritten();
        assertEquals(2, scheduler.scheduledCount());
    }

    @Test
    void explicitFlushAndCloseCancelPendingTasks() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        TimedFlushController controller = controller(Duration.ofSeconds(1L), scheduler, () -> { });

        controller.recordWritten();
        controller.flushed();
        assertEquals(0, scheduler.pendingCount());

        controller.recordWritten();
        controller.close();
        assertEquals(0, scheduler.pendingCount());
        assertThrows(IllegalStateException.class, scheduler::runNext);
    }

    @Test
    void zeroIntervalFlushesSynchronouslyWithoutScheduling() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        AtomicInteger flushes = new AtomicInteger();
        TimedFlushController controller = controller(Duration.ZERO, scheduler, flushes::incrementAndGet);

        controller.recordWritten();
        controller.recordWritten();

        assertEquals(2, flushes.get());
        assertEquals(0, scheduler.scheduledCount());
    }

    @Test
    void immediateSchedulerExecutionLeavesNoStalePendingTask() {
        AtomicInteger schedules = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        FlushScheduler immediate = (delay, action) -> {
            schedules.incrementAndGet();
            action.run();
            return () -> {
            };
        };
        TimedFlushController controller = controller(Duration.ofSeconds(1L), immediate, flushes::incrementAndGet);

        controller.recordWritten();
        controller.recordWritten();

        assertEquals(2, schedules.get());
        assertEquals(2, flushes.get());
    }

    @Test
    void schedulingFailureFallsBackToSynchronousFlush() {
        AtomicInteger flushes = new AtomicInteger();
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L),
                (delay, action) -> {
                    throw new IllegalStateException("scheduler unavailable");
                },
                new ImmediateDispatcher(),
                failure -> { },
                flushes::incrementAndGet);

        controller.recordWritten();

        assertEquals(1, flushes.get());
    }

    @Test
    void intervalIsBoundedToThePublicConfigurationRange() {
        assertThrows(IllegalArgumentException.class, () -> new TimedFlushController(
                Duration.ofNanos(-1L), () -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> new TimedFlushController(
                Duration.ofMinutes(1L).plusNanos(1L), () -> {
                }));
    }

    @Test
    void uncheckedTaskFailureCrossesTheBoundedDiagnosticBoundary() {
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        List<Throwable> reported = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("injected");
        TimedFlushController controller = new TimedFlushController(
                Duration.ofSeconds(1L), scheduler, new ImmediateDispatcher(), reported::add, () -> {
                    throw failure;
                });

        controller.recordWritten();
        scheduler.runNext();

        assertEquals(1, reported.size());
        assertSame(failure, reported.get(0));
        controller.recordWritten();
        assertEquals(2, scheduler.scheduledCount());
        controller.close();
    }

    private static TimedFlushController controller(Duration interval, FlushScheduler scheduler, Runnable action) {
        return new TimedFlushController(interval, scheduler, new ImmediateDispatcher(), failure -> { }, action);
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
