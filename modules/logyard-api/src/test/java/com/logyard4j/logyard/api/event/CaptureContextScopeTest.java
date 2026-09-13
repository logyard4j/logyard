package com.logyard4j.logyard.api.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class CaptureContextScopeTest {
    @Test
    void restoresNestedScopesAfterSuccessAndFailure() {
        CaptureContext outer = CaptureContext.create();
        CaptureContext inner = CaptureContext.create();
        CaptureContext.within(outer, () -> {
            assertSame(outer, CaptureContext.currentOrCreate());
            CaptureContext.within(inner, () -> {
                assertSame(inner, CaptureContext.currentOrCreate());
                return null;
            });
            assertSame(outer, CaptureContext.currentOrCreate());
            assertThrows(IllegalStateException.class, () -> CaptureContext.within(inner, () -> {
                throw new IllegalStateException("capture failed");
            }));
            assertSame(outer, CaptureContext.currentOrCreate());
            return null;
        });
        assertFalse(outer == CaptureContext.currentOrCreate());
        assertFalse(inner == CaptureContext.currentOrCreate());
    }

    @Test
    void completedScopeReleasesItsCapturedApplicationObjects() throws Exception {
        awaitCollection(captureApplicationValue(false));
        awaitCollection(captureApplicationValue(true));
    }

    @Test
    void completedCaptureDoesNotPinAnApplicationClassLoader() throws Exception {
        awaitCollection(captureInIsolatedLoader());
    }

    @Test
    void virtualThreadChurnKeepsActiveCaptureScopesIndependent() throws Exception {
        CaptureContext outer = CaptureContext.create();
        CaptureContext.within(outer, () -> {
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                ArrayList<Future<?>> completed = new ArrayList<>();
                for (int index = 0; index < 10_000; index++) {
                    completed.add(workers.submit(() -> {
                        CaptureContext local = CaptureContext.create();
                        CaptureContext.within(local, () -> {
                            Thread.yield();
                            assertSame(local, CaptureContext.currentOrCreate());
                            return null;
                        });
                        assertFalse(local == CaptureContext.currentOrCreate());
                    }));
                }
                for (Future<?> task : completed) task.get(15, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertSame(outer, CaptureContext.currentOrCreate());
            return null;
        });
    }

    private static WeakReference<Object> captureApplicationValue(boolean fail) {
        Object value = new Object();
        CaptureContext context = CaptureContext.create();
        Runnable capture = () -> CaptureContext.within(context, () -> {
            ValueCapture.capture(value);
            if (fail) throw new IllegalStateException("capture failed");
            return null;
        });
        if (fail) assertThrows(IllegalStateException.class, capture::run);
        else capture.run();
        return new WeakReference<>(value);
    }

    private static WeakReference<ClassLoader> captureInIsolatedLoader() throws Exception {
        URL classes = LogEvent.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader())) {
            Class<?> level = Class.forName("com.logyard4j.logyard.api.Level", true, loader);
            Class<?> attributes = Class.forName(AttributeSet.class.getName(), true, loader);
            Class<?> event = Class.forName(LogEvent.class.getName(), true, loader);
            event.getConstructor(long.class, long.class, level, String.class, String.class, String.class,
                    Object[].class, attributes, Throwable.class, long.class, String.class)
                    .newInstance(1L, 1L, level.getField("INFO").get(null), "isolated", null, "hello",
                            new Object[0], attributes.getField("EMPTY").get(null), null, 1L, "test");
            return new WeakReference<>(loader);
        }
    }

    private static void awaitCollection(WeakReference<?> reference) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(reference.get(), "completed capture retained an application reference on this live thread");
    }
}
