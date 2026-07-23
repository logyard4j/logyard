package com.zsumz.logyard.jul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.jul.internal.event.JulLevelMapper;
import com.zsumz.logyard.jul.internal.event.JulMessageRenderer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }

}
