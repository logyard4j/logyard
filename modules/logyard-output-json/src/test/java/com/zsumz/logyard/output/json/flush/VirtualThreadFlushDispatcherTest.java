package com.zsumz.logyard.output.json.flush;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VirtualThreadFlushDispatcherTest {
    @Test
    void dispatchedFlushDoesNotRetainCallerContext() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader callerLoader = new ClassLoader(originalLoader) { };
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> inheritedValue = new AtomicReference<>();
        AtomicReference<ClassLoader> contextLoader = new AtomicReference<>();
        AtomicBoolean virtual = new AtomicBoolean();
        inherited.set("caller-state");
        Thread.currentThread().setContextClassLoader(callerLoader);
        try {
            FlushDispatcher.DispatchedFlush flush = VirtualThreadFlushDispatcher.INSTANCE.dispatch(() -> {
                inheritedValue.set(inherited.get());
                contextLoader.set(Thread.currentThread().getContextClassLoader());
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
        assertTrue(virtual.get());
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
}
