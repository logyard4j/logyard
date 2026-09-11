package com.logyard4j.output.json.flush;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedElasticFlushDispatcherTest {
    @Test
    void workerIsAnIsolatedDaemonPlatformThread() throws Exception {
        BoundedElasticFlushDispatcher dispatcher = new BoundedElasticFlushDispatcher(1, Duration.ofMillis(10L));
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader callerLoader = new ClassLoader(originalLoader) { };
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> inheritedValue = new AtomicReference<>();
        AtomicReference<ClassLoader> contextLoader = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        AtomicBoolean virtual = new AtomicBoolean(true);
        inherited.set("caller-state");
        Thread.currentThread().setContextClassLoader(callerLoader);
        try {
            FlushDispatcher.DispatchedFlush flush = dispatcher.dispatch(() -> {
                inheritedValue.set(inherited.get());
                contextLoader.set(Thread.currentThread().getContextClassLoader());
                daemon.set(Thread.currentThread().isDaemon());
                virtual.set(Thread.currentThread().isVirtual());
                completed.countDown();
            });

            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            flush.awaitCompletion();
            assertTrue(flush.completed());
        } finally {
            inherited.remove();
            Thread.currentThread().setContextClassLoader(originalLoader);
        }

        assertNull(inheritedValue.get());
        assertNull(contextLoader.get());
        assertTrue(daemon.get());
        assertFalse(virtual.get());
        awaitNoWorkers(dispatcher);
    }

    @Test
    void workerCountIsBoundedAndIdleWorkersRetire() throws Exception {
        BoundedElasticFlushDispatcher dispatcher = new BoundedElasticFlushDispatcher(3, Duration.ofMillis(10L));
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        List<FlushDispatcher.DispatchedFlush> dispatched = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                dispatched.add(dispatcher.dispatch(() -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                }));
            }
            assertTrue(entered.await(2L, TimeUnit.SECONDS));
            assertEquals(3, dispatcher.liveWorkers());
            assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch(() -> { }));
        } finally {
            release.countDown();
            dispatched.forEach(FlushDispatcher.DispatchedFlush::awaitCompletion);
        }
        awaitNoWorkers(dispatcher);
    }

    @Test
    void reusedWorkerDoesNotRetainAChangedContextClassLoader() {
        BoundedElasticFlushDispatcher dispatcher = new BoundedElasticFlushDispatcher(1, Duration.ofMillis(500L));
        ClassLoader leaked = new ClassLoader() { };
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        FlushDispatcher.DispatchedFlush first = dispatcher.dispatch(() -> {
            firstWorker.set(Thread.currentThread());
            Thread.currentThread().setContextClassLoader(leaked);
        });
        first.awaitCompletion();
        await(() -> firstWorker.get().getState() == Thread.State.TIMED_WAITING);
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        AtomicReference<ClassLoader> observed = new AtomicReference<>(leaked);

        FlushDispatcher.DispatchedFlush second = dispatcher.dispatch(() -> {
            secondWorker.set(Thread.currentThread());
            observed.set(Thread.currentThread().getContextClassLoader());
        });
        second.awaitCompletion();

        assertSame(firstWorker.get(), secondWorker.get());
        awaitNoWorkers(dispatcher);
        assertNull(observed.get());
        awaitNoWorkers(dispatcher);
    }

    @Test
    void productionKeepaliveReusesAWorkerAcrossTheDefaultFlushCadence() throws Exception {
        BoundedElasticFlushDispatcher dispatcher =
                new BoundedElasticFlushDispatcher(1, BoundedElasticFlushDispatcher.DEFAULT_IDLE_TIMEOUT);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        FlushDispatcher.DispatchedFlush first = dispatcher.dispatch(() -> firstWorker.set(Thread.currentThread()));
        first.awaitCompletion();

        Thread.sleep(Duration.ofMillis(1_100L));

        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        FlushDispatcher.DispatchedFlush second = dispatcher.dispatch(() -> secondWorker.set(Thread.currentThread()));
        second.awaitCompletion();

        assertSame(firstWorker.get(), secondWorker.get());
    }

    @Test
    void sharedDeadlineThreadIsDaemonAndDoesNotRetainCallerContext() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> inheritedValue = new AtomicReference<>();
        AtomicReference<ClassLoader> contextLoader = new AtomicReference<>();
        AtomicBoolean daemon = new AtomicBoolean();
        inherited.set("caller-state");
        try {
            FlushScheduler.shared().schedule(Duration.ZERO, () -> {
                inheritedValue.set(inherited.get());
                contextLoader.set(Thread.currentThread().getContextClassLoader());
                daemon.set(Thread.currentThread().isDaemon());
                completed.countDown();
            });
            assertTrue(completed.await(2L, TimeUnit.SECONDS));
        } finally {
            inherited.remove();
        }

        assertNull(inheritedValue.get());
        assertNull(contextLoader.get());
        assertTrue(daemon.get());
    }

    private static void awaitNoWorkers(BoundedElasticFlushDispatcher dispatcher) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4L);
        while (dispatcher.liveWorkers() != 0) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("flush workers did not retire");
            }
            Thread.onSpinWait();
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
}
