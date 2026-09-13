package com.logyard4j.logyard.systemlogger.internal.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import com.logyard4j.logyard.runtime.adapter.AdapterRuntimeAccess;
import com.logyard4j.logyard.runtime.adapter.BorrowedAdapterRuntime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Pins the System.Logger contract surface implemented by the Logyard adapter. */
final class LogyardSystemLoggerBehaviorTest {
    @Test
    void neverPublishesAtOffAndNeverReportsOffAsLoggable() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            System.Logger logger = logger(runtime, "off.probe");

            assertFalse(logger.isLoggable(System.Logger.Level.OFF));
            logger.log(System.Logger.Level.OFF, "never");
            logger.log(System.Logger.Level.OFF, "never {0}", "argument");
            logger.log(System.Logger.Level.OFF, () -> "never supplied");
            logger.log(System.Logger.Level.TRACE, "kept");
        }

        assertEquals(1, sink.events.size());
        assertEquals("kept", sink.events.getFirst().messageTemplate());
    }

    @Test
    void keepsIsLoggableConsistentWithWhatActuallyPublishes() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.WARN)) {
            System.Logger logger = logger(runtime, "threshold.probe");
            for (System.Logger.Level level : System.Logger.Level.values()) {
                boolean loggable = logger.isLoggable(level);
                int before = sink.events.size();
                logger.log(level, "probe " + level.name());
                assertEquals(loggable, sink.events.size() > before,
                        () -> "isLoggable disagreed with publication at " + level);
            }
        }

        assertEquals(2, sink.events.size(), "only WARNING and ERROR clear a WARN threshold");
    }

    @Test
    void evaluatesAMessageSupplierExactlyOnceAndOnlyWhenEnabled() {
        RecordingSink sink = new RecordingSink();
        AtomicInteger disabled = new AtomicInteger();
        AtomicInteger enabled = new AtomicInteger();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.WARN)) {
            System.Logger logger = logger(runtime, "supplier.probe");

            logger.log(System.Logger.Level.DEBUG, () -> {
                disabled.incrementAndGet();
                return "must not be built";
            });
            logger.log(System.Logger.Level.ERROR, () -> {
                enabled.incrementAndGet();
                return "built once";
            });
        }

        assertEquals(0, disabled.get(), "a disabled level must never evaluate its supplier");
        assertEquals(1, enabled.get(), "an enabled level must evaluate its supplier exactly once");
        assertEquals("built once", sink.events.getFirst().messageTemplate());
    }

    @Test
    void preservesModuleThreadAndTimestampMetadata() {
        RecordingSink sink = new RecordingSink();
        long before = System.currentTimeMillis();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            logger(runtime, "metadata.probe").log(System.Logger.Level.INFO, "captured");
        }

        LogEvent event = sink.events.getFirst();
        assertEquals("unnamed", event.attributes().get("java.module.name"));
        assertEquals("INFO", event.attributes().get("system.logger.level"));
        assertEquals(Thread.currentThread().getName(), event.threadName());
        assertTrue(event.timestampMillis() >= before);
        assertEquals("metadata.probe", event.loggerName());
    }

    @Test
    void recordsBundleAndTemplateAttributesOnlyWhenTheyAddInformation() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            System.Logger logger = logger(runtime, "attribute.probe");
            logger.log(System.Logger.Level.INFO, "order {0}", "A-42");
            logger.log(System.Logger.Level.INFO, "no placeholders", "ignored");
        }

        LogEvent rendered = sink.events.get(0);
        LogEvent literal = sink.events.get(1);
        assertEquals("order {0}", rendered.attributes().get("system.logger.message_template"));
        assertEquals("order A-42", rendered.messageTemplate());
        assertNull(literal.attributes().get("system.logger.message_template"));
        assertNull(literal.attributes().get("system.logger.resource_bundle"),
                "an absent bundle contributes no attribute");
    }

    @Test
    void flagsAFormatFailureWithLogyardsReservedDiagnosticAttribute() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            logger(runtime, "failure.probe")
                    .log(System.Logger.Level.WARNING, "broken {0,number,#", 1);
        }

        assertEquals(Boolean.TRUE,
                sink.events.getFirst().attributes().get("logyard.system_logger.message_format_failed"));
    }

    @Test
    void capturesTheThrowableSuppliedByTheThrowableOverload() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            logger(runtime, "throwable.probe").log(
                    System.Logger.Level.ERROR, "failed", new IllegalStateException("payment rejected"));
        }

        LogEvent event = sink.events.getFirst();
        assertNotNull(event.exception());
        assertEquals("payment rejected", event.exception().message());
        assertEquals(Level.ERROR, event.level());
    }

    @Test
    void rejectsBlankNamesAndNullLevelsButToleratesAnAbsentModule() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = runtime(sink, Level.TRACE)) {
            AdapterRuntimeAccess access = new BorrowedAdapterRuntime(runtime);
            assertThrows(NullPointerException.class, () -> new LogyardSystemLogger(null, module(), access));
            assertThrows(IllegalArgumentException.class, () -> new LogyardSystemLogger("  ", module(), access));
            assertEquals("trimmed", new LogyardSystemLogger("  trimmed  ", module(), access).getName());

            System.Logger moduleless = new LogyardSystemLogger("moduleless", null, access);
            moduleless.log(System.Logger.Level.INFO, "still published");
            assertThrows(NullPointerException.class, () -> moduleless.isLoggable(null));
        }

        assertEquals("unnamed", sink.events.getFirst().attributes().get("java.module.name"));
    }

    @Test
    void swallowsAnUnavailableRuntimeInBothIsLoggableAndLog() {
        AtomicInteger attempts = new AtomicInteger();
        AdapterRuntimeAccess unavailable = new AdapterRuntimeAccess() {
            @Override
            public LogyardRuntime runtime() {
                attempts.incrementAndGet();
                throw new IllegalStateException("runtime is not installed");
            }

            @Override
            public boolean initialized() {
                return false;
            }

            @Override
            public void close() {
            }
        };
        System.Logger logger = new LogyardSystemLogger("unavailable.probe", module(), unavailable);

        assertFalse(logger.isLoggable(System.Logger.Level.ERROR));
        logger.log(System.Logger.Level.ERROR, "dropped", new IllegalStateException("cause"));

        assertEquals(2, attempts.get(), "both entry points must retry rather than latch a failure");
    }

    private static Module module() {
        return LogyardSystemLoggerBehaviorTest.class.getModule();
    }

    private static System.Logger logger(LogyardRuntime runtime, String name) {
        return new LogyardSystemLogger(name, module(), new BorrowedAdapterRuntime(runtime));
    }

    private static DefaultLogyardRuntime runtime(EventSink sink, Level level) {
        return new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(level, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1)));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
