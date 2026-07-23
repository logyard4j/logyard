package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdapterRuntimeCoordinatorTest {
    private static final long TEST_TRANSITION_WAIT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final LogyardConfig CONFIG = LogyardConfigLoader.parse(
            """
                    schema = 1
                    [loggers]
                    root = { outputs = ["console"] }
                    [outputs.console]
                    type = "console"
                    """,
            "adapter-runtime-coordinator-test.toml",
            Path.of("."),
            Map.of());

    @Test
    void sharesOneOwnedRuntimeUntilTheLastLeaseCloses() {
        AtomicReference<LogyardRuntime> current = new AtomicReference<>();
        AtomicReference<TestRuntime> created = new AtomicReference<>();
        AtomicInteger bootstraps = new AtomicInteger();
        AtomicInteger hooks = new AtomicInteger();
        AdapterRuntimeCoordinator coordinator = coordinator(
                () -> {
                    bootstraps.incrementAndGet();
                    TestRuntime runtime = new TestRuntime();
                    created.set(runtime);
                    current.set(runtime);
                    return new RuntimeBundle(null, CONFIG, runtime);
                },
                current,
                hooks);

        AdapterRuntimeHandle first = coordinator.resolve("slf4j2");
        AdapterRuntimeHandle second = coordinator.resolve("jul");

        assertSame(first.runtime(), second.runtime());
        assertTrue(first.ownsRuntime());
        assertTrue(second.ownsRuntime());
        assertEquals(1, bootstraps.get());
        assertEquals(1, hooks.get());

        first.close();
        assertEquals(0, created.get().closeCount());
        assertTrue(second.initialized());

        second.close();
        assertEquals(1, created.get().closeCount());
        assertFalse(first.initialized());
        assertFalse(second.initialized());
    }

    @Test
    void borrowsAnApplicationRuntimeWithoutTakingOwnership() {
        TestRuntime applicationRuntime = new TestRuntime();
        AtomicReference<LogyardRuntime> current = new AtomicReference<>(applicationRuntime);
        AtomicInteger bootstraps = new AtomicInteger();
        AdapterRuntimeCoordinator coordinator = coordinator(
                () -> {
                    bootstraps.incrementAndGet();
                    throw new AssertionError("bootstrap must not run");
                },
                current,
                new AtomicInteger());

        AdapterRuntimeHandle handle = coordinator.resolve("system-logger");

        assertSame(applicationRuntime, handle.runtime());
        assertFalse(handle.ownsRuntime());
        handle.close();
        assertEquals(0, applicationRuntime.closeCount());
        assertEquals(0, bootstraps.get());
    }

    @Test
    void retiresAStaleOwnedRuntimeBeforeStartingItsReplacement() {
        AtomicReference<LogyardRuntime> current = new AtomicReference<>();
        AtomicReference<TestRuntime> firstRuntime = new AtomicReference<>();
        AtomicReference<TestRuntime> secondRuntime = new AtomicReference<>();
        AtomicInteger bootstraps = new AtomicInteger();
        AdapterRuntimeCoordinator coordinator = coordinator(
                () -> {
                    TestRuntime runtime = new TestRuntime();
                    if (bootstraps.getAndIncrement() == 0) {
                        firstRuntime.set(runtime);
                    } else {
                        secondRuntime.set(runtime);
                    }
                    current.set(runtime);
                    return new RuntimeBundle(null, CONFIG, runtime);
                },
                current,
                new AtomicInteger());

        AdapterRuntimeHandle stale = coordinator.resolve("slf4j2");
        current.set(null);
        AdapterRuntimeHandle replacement = coordinator.resolve("jul");

        assertEquals(1, firstRuntime.get().closeCount());
        assertFalse(stale.initialized());
        assertSame(secondRuntime.get(), replacement.runtime());
        stale.close();
        replacement.close();
        assertEquals(1, secondRuntime.get().closeCount());
    }

    private static AdapterRuntimeCoordinator coordinator(
            Supplier<RuntimeBundle> bootstrap,
            AtomicReference<LogyardRuntime> current,
            AtomicInteger hooks) {
        return new AdapterRuntimeCoordinator(
                bootstrap,
                current::get,
                (adapterName, shutdown) -> hooks.incrementAndGet(),
                TEST_TRANSITION_WAIT_NANOS);
    }

    private static final class TestRuntime implements LogyardRuntime {
        private final AtomicInteger closes = new AtomicInteger();

        int closeCount() {
            return closes.get();
        }

        @Override
        public LogyardLogger logger(Class<?> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LogyardLogger logger(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EffectiveRoute explain(String loggerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeHealth health() {
            return RuntimeHealth.from(List.of());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
