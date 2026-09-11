package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.LogBuilder;
import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActiveLogBuilderTest {
    @Test
    void defersFluentSuppliersUntilPublicationCapture() {
        List<LogEvent> events = new ArrayList<>();
        AtomicInteger evaluations = new AtomicInteger();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            LogBuilder builder = runtime.logger("test.Fluent")
                    .atInfo()
                    .argumentLazy(() -> evaluations.incrementAndGet())
                    .addLazy("supplied", () -> evaluations.incrementAndGet());

            assertEquals(0, evaluations.get());
            builder.log("values {}");

            assertEquals(2, evaluations.get());
            assertEquals(1, events.size());
            assertEquals(1, events.getFirst().argumentAt(0));
            assertEquals(2, events.getFirst().attributes().get("supplied"));
        }
    }

    @Test
    void isolatesSupplierAndCallerValueFailuresFromApplicationControlFlow() {
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            LogyardLogger logger = runtime.logger("test.Fluent");
            assertDoesNotThrow(() -> logger.atInfo().argumentLazy(() -> {
                throw new AssertionError("supplier boom");
            }).log("supplier"));
            assertDoesNotThrow(() -> logger.atInfo().add("hostile", hostileMap()).log("map"));
            assertEquals(1, events.size());
            assertEquals(java.util.Map.of(), events.getFirst().attributes().get("hostile"));
        }
    }

    @Test
    void retainsFatalAndInterruptionSemanticsAtTheCaptureBoundary() {
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> { })) {
            TestVirtualMachineError fatal = new TestVirtualMachineError();
            assertSame(fatal, assertThrows(TestVirtualMachineError.class, () -> runtime.logger("test.Fatal")
                    .atInfo()
                    .argumentLazy(() -> {
                        throw fatal;
                    })
                    .log("fatal")));

            try {
                assertDoesNotThrow(() -> runtime.logger("test.Interrupted")
                        .atInfo()
                        .argumentLazy(() -> sneakyThrow(new InterruptedException("interrupted")))
                        .log("interrupted"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void keepsProgrammerContractFailuresSynchronous() {
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(ignored -> { })) {
            LogBuilder builder = runtime.logger("test.Contracts").atInfo();

            assertThrows(NullPointerException.class, () -> builder.add(null, "value"));
            assertThrows(IllegalArgumentException.class, () -> builder.add(" ", "value"));
            assertThrows(IllegalArgumentException.class, () -> builder.add(" ".repeat(256) + "x", "value"));
            assertThrows(NullPointerException.class, () -> builder.addLazy("key", null));
            assertThrows(NullPointerException.class, () -> builder.argumentLazy(null));
            assertThrows(NullPointerException.class, () -> builder.addAll(null));
            builder.log("once");
            assertThrows(IllegalStateException.class, () -> builder.log("twice"));
            assertThrows(IllegalStateException.class, () -> builder.event("late"));
            assertThrows(IllegalStateException.class, () -> builder.message("late"));
            assertThrows(IllegalStateException.class, () -> builder.argument("late"));
            assertThrows(IllegalStateException.class, () -> builder.argumentLazy(() -> "late"));
            assertThrows(IllegalStateException.class, () -> builder.add("late", "value"));
            assertThrows(IllegalStateException.class, () -> builder.addLazy("late", () -> "value"));
            assertThrows(IllegalStateException.class, () -> builder.cause(new IllegalStateException("late")));
        }
    }

    @Test
    void nativeLongKeysPreserveCaptureTruncationProvenance() {
        List<LogEvent> events = new ArrayList<>();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(events::add)) {
            runtime.logger("test.LongKey")
                    .atInfo()
                    .add("x".repeat(1_000) + ".authorization", "secret")
                    .log("captured");
        }

        assertEquals(true, events.getFirst().attributes().get("logyard.capture.truncated"));
    }

    private static AbstractMap<String, Object> hostileMap() {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of();
            }

            @Override
            public int size() {
                throw new AssertionError("size boom");
            }
        };
    }

    private static Object sneakyThrow(Throwable failure) {
        return ActiveLogBuilderTest.<RuntimeException, Object>throwUnchecked(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
