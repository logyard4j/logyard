package com.logyard4j.logyard.spring.boot.internal.lifecycle;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.jul.LogyardHandler;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FrameworkRuntimeLifecycleTest {
    @Test
    void lifecycleEntryPointsDoNotHoldTheObjectMonitorAcrossExternalWork() throws Exception {
        for (String methodName : new String[] {"startEarly", "configure", "runtime", "close"}) {
            Method method = "configure".equals(methodName)
                    ? FrameworkRuntimeLifecycle.class.getDeclaredMethod(
                            methodName,
                            com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource.class)
                    : FrameworkRuntimeLifecycle.class.getDeclaredMethod(methodName);
            assertFalse(Modifier.isSynchronized(method.getModifiers()));
        }
    }

    @Test
    void closeSupersedingConfigureReleasesTheOperationOwnedJulLease() throws Exception {
        Logger root = rootLogger();
        Handler[] originalHandlers = root.getHandlers();
        Level originalLevel = root.getLevel();
        CountDownLatch replacementAcquired = new CountDownLatch(1);
        CountDownLatch allowReplacement = new CountDownLatch(1);
        AtomicInteger acquisitions = new AtomicInteger();
        FrameworkRuntimeLifecycle lifecycle = new FrameworkRuntimeLifecycle((owner, source) -> {
            var acquired = LogyardBootstrap.acquire(owner, source);
            if (acquisitions.incrementAndGet() == 2) {
                replacementAcquired.countDown();
                await(allowReplacement);
            }
            return acquired;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            lifecycle.configure(source("first", "info"));
            Future<?> configuring = executor.submit(() -> lifecycle.configure(source("second", "debug")));
            assertTrue(replacementAcquired.await(2L, TimeUnit.SECONDS));

            lifecycle.close();
            allowReplacement.countDown();
            ExecutionException superseded = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class,
                    () -> configuring.get(3L, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, superseded.getCause());
            assertArrayEquals(originalHandlers, root.getHandlers());
            assertEquals(originalLevel, root.getLevel());
            assertFalse(Arrays.stream(root.getHandlers()).anyMatch(LogyardHandler.class::isInstance));

            FrameworkRuntimeLifecycle later = new FrameworkRuntimeLifecycle();
            later.configure(source("later", "warn"));
            assertEquals(1L, Arrays.stream(root.getHandlers()).filter(LogyardHandler.class::isInstance).count());
            later.close();
            assertArrayEquals(originalHandlers, root.getHandlers());
            assertEquals(originalLevel, root.getLevel());
        } finally {
            allowReplacement.countDown();
            lifecycle.close();
            Logyard.shutdown();
            executor.shutdownNow();
        }
    }

    private static LogyardConfigurationSource source(String name, String level) {
        return LogyardConfigurationSource.text(name, """
                schema = 1
                [runtime]
                watch = false
                internal_status = "off"
                shutdown_timeout = "2s"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(level), Path.of("."));
    }

    private static Logger rootLogger() {
        Logger root = LogManager.getLogManager().getLogger("");
        if (root == null) {
            throw new IllegalStateException("JUL root logger is unavailable");
        }
        return root;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test barrier interrupted", interrupted);
        }
    }
}
