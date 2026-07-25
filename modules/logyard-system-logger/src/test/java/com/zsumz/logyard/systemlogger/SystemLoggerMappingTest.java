package com.zsumz.logyard.systemlogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.runtime.adapter.BorrowedAdapterRuntime;
import com.zsumz.logyard.systemlogger.internal.factory.LogyardSystemLogger;
import com.zsumz.logyard.systemlogger.internal.event.SystemLevelMapper;
import com.zsumz.logyard.systemlogger.internal.event.SystemMessageRenderer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SystemLoggerMappingTest {
    @Test
    void mapsJdkLevels() {
        assertEquals(Level.TRACE, SystemLevelMapper.toLogyard(System.Logger.Level.TRACE));
        assertEquals(Level.DEBUG, SystemLevelMapper.toLogyard(System.Logger.Level.DEBUG));
        assertEquals(Level.INFO, SystemLevelMapper.toLogyard(System.Logger.Level.INFO));
        assertEquals(Level.WARN, SystemLevelMapper.toLogyard(System.Logger.Level.WARNING));
        assertEquals(Level.ERROR, SystemLevelMapper.toLogyard(System.Logger.Level.ERROR));
    }

    @Test
    void rendersMessageFormatParameters() {
        SystemMessageRenderer.Result result =
                SystemMessageRenderer.render(null, "Order {0}", new Object[] {"A-42"});
        assertEquals("Order A-42", result.message());
        assertEquals("Order {0}", result.template());
    }

    @Test
    void boundsRecursiveChoiceFormatExpansion() {
        SystemMessageRenderer.Result result = SystemMessageRenderer.render(
                null,
                "{0,choice,0#" + "'{1}'".repeat(1_600) + "}",
                new Object[] {0, "x".repeat(2_048)});

        assertTrue(result.message().contains("format expansion omitted"));
    }

    @Test
    void boundsRepeatedDefaultNumberExpansion() {
        SystemMessageRenderer.Result result = SystemMessageRenderer.render(
                null,
                "{0}".repeat(490),
                new Object[] {new BigDecimal(BigInteger.ONE, -2_048)});

        assertTrue(result.message().contains("format expansion omitted"));
    }

    @Test
    void publishesWithModuleAndTemplateMetadata() {
        RecordingSink sink = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1));
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            System.Logger logger = new LogyardSystemLogger(
                    "orders.system",
                    SystemLoggerMappingTest.class.getModule(),
                    new BorrowedAdapterRuntime(runtime));
            logger.log(System.Logger.Level.INFO, "Order {0}", "A-42");
        }

        assertEquals(1, sink.events.size());
        LogEvent event = sink.events.getFirst();
        assertEquals(Level.INFO, event.level());
        assertEquals("Order A-42", event.messageTemplate());
        assertEquals("Order {0}", event.attributes().get("system.logger.message_template"));
        assertEquals("unnamed", event.attributes().get("java.module.name"));
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }

}
