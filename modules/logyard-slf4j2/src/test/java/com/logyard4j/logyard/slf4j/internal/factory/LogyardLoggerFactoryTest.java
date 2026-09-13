package com.logyard4j.logyard.slf4j.internal.factory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import com.logyard4j.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.logyard4j.logyard.slf4j.internal.event.Slf4jEventMapper;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.spi.LocationAwareLogger;

final class LogyardLoggerFactoryTest {
    @Test
    void cachesByNameAndRejectsInvalidNames() {
        EventSink sink = event -> { };
        try (LogyardRuntime runtime = new DefaultLogyardRuntime(new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of()))) {
            LogyardLoggerFactory factory = new LogyardLoggerFactory(
                    runtime,
                    new Slf4jEventMapper(
                            new LogyardMdcAdapter(),
                            new ContextSnapshotPolicy(List.of())));
            Logger first = factory.getLogger("test.Logger");
            Logger second = factory.getLogger("test.Logger");
            assertSame(first, second);
            assertFalse(first instanceof LocationAwareLogger);
            assertThrows(NullPointerException.class, () -> factory.getLogger(null));
            assertThrows(IllegalArgumentException.class, () -> factory.getLogger(" "));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> factory.getLogger("x".repeat(CaptureLimits.MAX_NAME_CHARS + 1)));
        }
    }

    @Test
    void switchableFactoryNeverReturnsNullBeforeOrAfterFailure() {
        SwitchableLoggerFactory switchable = new SwitchableLoggerFactory();
        assertThrows(IllegalStateException.class, () -> switchable.getLogger("test"));
        switchable.fail(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> switchable.getLogger("test"));
    }
}
