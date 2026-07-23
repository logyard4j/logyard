package com.zsumz.logyard.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LogyardLifecycleTest {
    @Test
    void conditionalShutdownCannotRemoveAReplacementRuntime() {
        TestRuntime installed = new TestRuntime();
        TestRuntime other = new TestRuntime();
        Logyard.initialize(installed);
        try {
            assertFalse(Logyard.shutdownIfCurrent(other));
            assertSame(installed, Logyard.runtime());
            assertEquals(0, installed.closes());
            assertEquals(0, other.closes());

            assertTrue(Logyard.shutdownIfCurrent(installed));
            assertFalse(Logyard.isInitialized());
            assertEquals(1, installed.closes());
        } finally {
            Logyard.shutdown();
        }
    }

    private static final class TestRuntime implements LogyardRuntime {
        private final AtomicInteger closes = new AtomicInteger();

        int closes() {
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
