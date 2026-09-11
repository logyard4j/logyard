package com.logyard4j.jul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.routing.RouteDefinition;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.jul.internal.event.JulLevelMapper;
import com.logyard4j.jul.internal.event.JulMessageRenderer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

final class LogyardHandlerTest {
    @Test
    void mapsJulSeverityDeterministically() {
        assertEquals(Level.ERROR, JulLevelMapper.toLogyard(java.util.logging.Level.SEVERE));
        assertEquals(Level.WARN, JulLevelMapper.toLogyard(java.util.logging.Level.WARNING));
        assertEquals(Level.INFO, JulLevelMapper.toLogyard(java.util.logging.Level.INFO));
        assertEquals(Level.DEBUG, JulLevelMapper.toLogyard(java.util.logging.Level.FINE));
        assertEquals(Level.TRACE, JulLevelMapper.toLogyard(java.util.logging.Level.FINEST));
    }

    @Test
    void rendersJulMessageFormatParameters() {
        LogRecord record = new LogRecord(java.util.logging.Level.INFO, "Order {0} has {1} items");
        record.setParameters(new Object[] {"A-42", 3});
        assertEquals("Order A-42 has 3 items", JulMessageRenderer.render(record).message());
    }

    @Test
    void boundsRecursiveChoiceFormatExpansion() {
        LogRecord record = new LogRecord(java.util.logging.Level.INFO, "{0,choice,0#" + "'{1}'".repeat(1_600) + "}");
        record.setParameters(new Object[] {0, "x".repeat(2_048)});

        assertTrue(JulMessageRenderer.render(record).message().contains("format expansion omitted"));
    }

    @Test
    void boundsRepeatedDefaultNumberExpansion() {
        LogRecord record = new LogRecord(java.util.logging.Level.INFO, "{0}".repeat(490));
        record.setParameters(new Object[] {new BigDecimal(BigInteger.ONE, -2_048)});

        assertTrue(JulMessageRenderer.render(record).message().contains("format expansion omitted"));
    }

    @Test
    void publishesACompleteRecordIntoAnApplicationOwnedRuntime() {
        RecordingSink sink = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1));
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            LogRecord record = new LogRecord(java.util.logging.Level.WARNING, "Order {0}");
            record.setLoggerName("orders.jul");
            record.setParameters(new Object[] {"A-42"});
            record.setSourceClassName("orders.OrderService");
            record.setSourceMethodName("accept");
            handler.publish(record);
            handler.close();
        }

        assertEquals(1, sink.events.size());
        LogEvent event = sink.events.getFirst();
        assertEquals(Level.WARN, event.level());
        assertEquals("Order A-42", event.messageTemplate());
        assertEquals("Order {0}", event.attributes().get("jul.message_template"));
        assertEquals("orders.OrderService", event.attributes().get("code.namespace"));
        assertTrue(event.timestampMillis() > 0L);
    }

    @Test
    void closeStopsAdmissionAndWaitsForAnInflightPublication() throws Exception {
        BlockingSink sink = new BlockingSink();
        try (DefaultLogyardRuntime runtime = DefaultLogyardRuntime.consoleOnly(sink)) {
            LogyardHandler handler = new LogyardHandler(runtime);
            Thread publisher = Thread.ofPlatform().start(
                    () -> handler.publish(new LogRecord(java.util.logging.Level.INFO, "first")));
            assertTrue(sink.entered.await(1, TimeUnit.SECONDS));

            CountDownLatch closed = new CountDownLatch(1);
            Thread closer = Thread.ofPlatform().start(() -> {
                handler.close();
                closed.countDown();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!waiting(closer) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(waiting(closer));
            handler.publish(new LogRecord(java.util.logging.Level.INFO, "rejected"));
            assertEquals(1L, closed.getCount());

            sink.release.countDown();
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            publisher.join();
            closer.join();
            assertEquals(1, sink.events.size());
        }
    }

    private static boolean waiting(Thread thread) {
        return thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING;
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }

    private static final class BlockingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void accept(LogEvent event) {
            events.add(event);
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

}
