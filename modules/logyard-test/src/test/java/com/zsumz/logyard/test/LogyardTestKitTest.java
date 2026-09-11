package com.zsumz.logyard.test;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.context.ContextScope;
import com.zsumz.logyard.api.context.LogContext;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardTestKitTest {
    @Test
    void eventsAreVisibleImmediatelyAfterLogging() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Sync").info("first");
            assertEquals(1, kit.events().size(), "delivery into a kit must be synchronous");
            kit.logger("test.Sync").info("second");
            assertEquals(2, kit.events().size());
            assertEquals("first", kit.events().all().getFirst().renderedMessage());
        }
    }

    @Test
    void rootRoutesEveryLevelIncludingTrace() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            LogyardLogger logger = kit.logger(LogyardTestKitTest.class);
            assertTrue(logger.isTraceEnabled(), "the kit routes its root logger at TRACE");
            for (Level level : Level.values()) {
                logger.at(level).log("at " + level);
            }
            assertEquals(List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR),
                    kit.events().all().stream().map(LogEvent::level).toList());
            assertEquals(LogyardTestKitTest.class.getName(), kit.events().all().getFirst().loggerName());
        }
    }

    @Test
    void scopedContextAndCurriedPresetsReachCapturedEvents() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            try (ContextScope scope = LogContext.push("tenant", "north")) {
                assertEquals("north", LogContext.current().toMap().get("tenant"), "the scope is open: " + scope);
                kit.logger("test.Context").with("component", "checkout").atInfo().add("order.id", 7L).log("scoped");
            }
            Map<String, Object> attributes = kit.events().all().getFirst().attributes().toMap();
            assertEquals("north", attributes.get("tenant"), "scoped context must be merged at capture");
            assertEquals("checkout", attributes.get("component"), "curried presets must be merged at capture");
            assertEquals(7L, attributes.get("order.id"));
        }
    }

    @Test
    void exceptionsAreCapturedAsDetachedSnapshots() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Failure").atError().cause(new IllegalStateException("broken")).log("failed");
            LogEvent event = kit.events().all().getFirst();
            assertNotNull(event.exception());
            assertEquals(IllegalStateException.class.getName(), event.exception().type());
            assertEquals("broken", event.exception().message());
        }
    }

    @Test
    void clearDiscardsRecordedEventsAndKeepsRecording() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Clear").info("before");
            kit.events().clear();
            assertEquals(0, kit.events().size());
            assertEquals(List.of(), kit.events().all());
            kit.logger("test.Clear").info("after");
            assertEquals(List.of("after"), kit.events().all().stream().map(LogEvent::renderedMessage).toList());
        }
    }

    @Test
    void recorderSnapshotsAreImmutableAndStableAcrossPublication() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Snapshot").info("one");
            List<LogEvent> snapshot = kit.events().all();
            kit.logger("test.Snapshot").info("two");
            assertEquals(1, snapshot.size(), "a snapshot must not observe later publications");
            assertEquals(2, kit.events().size());
        }
    }

    @Test
    void runtimeAndLoggerConveniencesShareTheKitRuntime() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.runtime().logger("test.Runtime").info("through the runtime");
            kit.logger("test.Runtime").info("through the convenience");
            assertEquals(2, kit.events().size());
            assertEquals(Level.TRACE, kit.runtime().explain("test.Runtime").level());
        }
    }

    @Test
    void closeIsIdempotentAndLoggingAfterCloseIsDroppedQuietly() {
        LogyardTestKit kit = LogyardTestKit.isolated();
        LogyardLogger logger = kit.logger("test.Closed");
        logger.info("before close");
        kit.close();
        kit.close();
        // A retired runtime refuses new publications instead of throwing, so a test that logs from a
        // shutdown hook or a late thread still fails on its assertions rather than on an exception.
        logger.info("after close");
        kit.logger("test.Closed").info("after close from a fresh logger");
        assertEquals(1, kit.events().size(), "a closed kit records nothing further");
        assertEquals("before close", kit.events().all().getFirst().renderedMessage());
    }
}
