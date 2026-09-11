package com.logyard4j.jul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Pins what one JUL record contributes to a captured Logyard event. */
final class LogyardHandlerCaptureTest {
    @Test
    void resolvesJulsLazyCallerMetadataBeforeTheEventLeavesThePublishingThread() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            Logger logger = Logger.getLogger("jul.caller." + System.nanoTime());
            logger.setUseParentHandlers(false);
            logger.setLevel(java.util.logging.Level.ALL);
            LogyardHandler handler = new LogyardHandler(runtime);
            logger.addHandler(handler);
            try {
                logger.info("inferred caller");
            } finally {
                logger.removeHandler(handler);
            }
        }

        LogEvent event = sink.events.getFirst();
        assertEquals(getClass().getName(), event.attributes().get("code.namespace"));
        assertEquals(
                "resolvesJulsLazyCallerMetadataBeforeTheEventLeavesThePublishingThread",
                event.attributes().get("code.function.name"));
    }

    @Test
    void preservesTheRecordInstantSequenceAndOriginatingThreadIdentifier() throws Exception {
        List<LogRecord> created = new ArrayList<>();
        Thread creator = new Thread(
                () -> created.add(new LogRecord(java.util.logging.Level.WARNING, "from worker")),
                "jul-record-creator");
        creator.start();
        creator.join();
        LogRecord record = created.getFirst();
        record.setLoggerName("orders.worker");
        record.setInstant(java.time.Instant.ofEpochMilli(1_234_567_890L));

        LogEvent event = publish(record);

        assertEquals(1_234_567_890L, event.timestampMillis());
        assertEquals(creator.threadId(), event.threadId());
        assertEquals(Thread.currentThread().getName(), event.threadName(),
                "JUL carries no thread name, so the publishing thread supplies it");
        assertEquals(record.getSequenceNumber(), event.attributes().get("jul.sequence_number"));
        assertEquals("WARNING", event.attributes().get("jul.level"));
        assertEquals(Level.WARN, event.level());
    }

    @Test
    void keepsTheTemplateOnlyWhenRenderingActuallyChangedIt() {
        LogRecord unrendered = new LogRecord(java.util.logging.Level.INFO, "no placeholders here");
        LogRecord rendered = new LogRecord(java.util.logging.Level.INFO, "order {0}");
        rendered.setParameters(new Object[] {"A-42"});

        assertNull(publish(unrendered).attributes().get("jul.message_template"));
        LogEvent event = publish(rendered);
        assertEquals("order {0}", event.attributes().get("jul.message_template"));
        assertEquals("order A-42", event.messageTemplate());
    }

    @Test
    void flagsAFormatFailureWithLogyardsReservedDiagnosticAttribute() {
        LogRecord record = new LogRecord(java.util.logging.Level.INFO, "broken {0,number,#");
        record.setParameters(new Object[] {1});

        LogEvent event = publish(record);

        assertEquals(Boolean.TRUE, event.attributes().get("logyard.jul.message_format_failed"));
    }

    @Test
    void recordsTheDeclaredBundleNameButNotAnAnonymousBundle() {
        LogRecord anonymous = new LogRecord(java.util.logging.Level.INFO, "greeting");
        anonymous.setResourceBundle(bundle());

        LogEvent event = publish(anonymous);

        assertEquals("Hallo {0}", event.messageTemplate());
        assertNull(event.attributes().get("jul.resource_bundle"),
                "an anonymous bundle has no base name to record");
    }

    @Test
    void fallsBackToASyntheticLoggerNameForNullAndBlankRecordLoggers() {
        LogRecord anonymous = new LogRecord(java.util.logging.Level.INFO, "anonymous");
        LogRecord blank = new LogRecord(java.util.logging.Level.INFO, "blank");
        blank.setLoggerName("   ");

        assertEquals("java.util.logging", publish(anonymous).loggerName());
        assertEquals("java.util.logging", publish(blank).loggerName());
    }

    @Test
    void neverPublishesAnOffValuedRecordEvenFromACustomLevelSubclass() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            handler.publish(new LogRecord(java.util.logging.Level.OFF, "singleton off"));
            handler.publish(new LogRecord(new ShadowOff(), "value-equal off"));
            handler.publish(new LogRecord(java.util.logging.Level.SEVERE, "kept"));
        }

        assertEquals(1, sink.events.size());
        assertEquals("kept", sink.events.getFirst().messageTemplate());
    }

    @Test
    void ignoresANullRecordWithoutTouchingTheRuntime() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            handler.publish(null);
            handler.flush();
        }

        assertTrue(sink.events.isEmpty());
    }

    @Test
    void handlerAcceptsEveryLevelSoRoutingStaysWithLogyard() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            assertSame(java.util.logging.Level.ALL, handler.getLevel());
            assertTrue(handler.isLoggable(new LogRecord(java.util.logging.Level.FINEST, "trace")));
        }
    }

    private LogEvent publish(LogRecord record) {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            new LogyardHandler(runtime).publish(record);
        }
        return sink.events.getFirst();
    }

    private static ResourceBundle bundle() {
        return new ResourceBundle() {
            @Override
            protected Object handleGetObject(String key) {
                return "greeting".equals(key) ? "Hallo {0}" : null;
            }

            @Override
            public Enumeration<String> getKeys() {
                return Collections.enumeration(List.of("greeting"));
            }
        };
    }

    private static final class ShadowOff extends java.util.logging.Level {
        private static final long serialVersionUID = 1L;

        private ShadowOff() {
            super("SHADOW_OFF", Integer.MAX_VALUE);
        }
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
