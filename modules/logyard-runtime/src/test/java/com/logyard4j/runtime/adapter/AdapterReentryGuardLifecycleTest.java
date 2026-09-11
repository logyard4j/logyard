package com.logyard4j.runtime.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class AdapterReentryGuardLifecycleTest {
    @Test
    void retainedThreadStateDoesNotPinTheLoadingClassLoader() throws Exception {
        WeakReference<ClassLoader> unloaded = useIsolatedGuard();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (unloaded.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(unloaded.get(), "the guard's thread-local value retained its application class loader");
    }

    @Test
    void virtualThreadChurnKeepsEachThreadsGuardIndependent() throws Exception {
        AdapterReentryGuard guard = new AdapterReentryGuard();
        assertTrue(guard.enter());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            ArrayList<Future<?>> completed = new ArrayList<>();
            for (int index = 0; index < 10_000; index++) {
                completed.add(workers.submit(() -> {
                    for (int cycle = 0; cycle < 10; cycle++) {
                        assertTrue(guard.enter());
                        try {
                            assertFalse(new AdapterReentryGuard().enter());
                            Thread.yield();
                            assertFalse(guard.enter());
                        } finally {
                            guard.exit();
                        }
                    }
                }));
            }
            for (Future<?> task : completed) {
                task.get(15, TimeUnit.SECONDS);
            }
            assertFalse(guard.enter());
        } finally {
            guard.exit();
        }
        assertTrue(guard.enter());
        guard.exit();
    }

    private static WeakReference<ClassLoader> useIsolatedGuard() throws Exception {
        URL classes = AdapterReentryGuard.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(AdapterReentryGuard.class.getName(), true, loader);
            Object guard = type.getConstructor().newInstance();
            assertTrue((Boolean) type.getMethod("enter").invoke(guard));
            type.getMethod("exit").invoke(guard);
            return new WeakReference<>(loader);
        }
    }
}
