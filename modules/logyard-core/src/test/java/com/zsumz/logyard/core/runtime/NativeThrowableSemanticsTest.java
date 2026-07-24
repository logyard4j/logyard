package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeThrowableSemanticsTest {
    @Test
    void treatsTheSecondFixedArgumentAsTheEventExceptionAtEveryLevel() {
        RecordingSink sink = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.TRACE, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of());
        IllegalStateException failure = new HostileFailure();
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan)) {
            LogyardLogger logger = runtime.logger("test.Throwable");
            logger.trace("value {}", "trace", failure);
            logger.debug("value {}", "debug", failure);
            logger.info("value {}", "info", failure);
            logger.warn("value {}", "warn", failure);
            logger.error("value {}", "error", failure);
            logger.info("two {} {}", "value", failure);
            logger.atInfo().argument(new IllegalStateException("structured data")).log("fluent {}");
        }

        assertEquals(7, sink.events.size());
        for (int index = 0; index < 6; index++) {
            LogEvent event = sink.events.get(index);
            assertEquals(1, event.argumentCount());
            assertEquals(HostileFailure.class.getName(), event.exception().type());
            assertTrue(event.exception().message().contains("message accessor failed"));
            assertTrue(event.exception().frames().getFirst().getFileName().contains("stack trace accessor failed"));
        }
        assertEquals("two value {}", sink.events.get(5).renderedMessage());
        LogEvent fluent = sink.events.get(6);
        assertNull(fluent.exception(), "an explicit fluent Throwable argument must remain data");
        assertTrue(String.valueOf(fluent.argumentAt(0)).contains("IllegalStateException"));
    }

    private static final class HostileFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new AssertionError("hostile message");
        }

        @Override
        public StackTraceElement[] getStackTrace() {
            throw new AssertionError("hostile stack trace");
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
