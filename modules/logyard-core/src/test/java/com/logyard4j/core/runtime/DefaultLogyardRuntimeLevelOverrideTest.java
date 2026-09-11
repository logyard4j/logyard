package com.logyard4j.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardLogger;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.core.level.RuntimeLevelOverride;
import com.logyard4j.core.routing.RouteDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class DefaultLogyardRuntimeLevelOverrideTest {
    @Test
    void hierarchicalOverridesRecompileExistingAndFutureLoggerControls() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(sink, Level.INFO, Level.WARN))) {
            LogyardLogger service = runtime.logger("com.acme.Service");
            LogyardLogger noisy = runtime.logger("com.acme.noisy.Client");
            LogyardLogger unrelated = runtime.logger("org.example.Service");
            assertFalse(service.isInfoEnabled());
            assertTrue(unrelated.isInfoEnabled());

            runtime.setLevelOverride("com.acme", RuntimeLevelOverride.threshold(Level.DEBUG));
            assertTrue(service.isDebugEnabled());
            assertTrue(noisy.isDebugEnabled());
            assertEquals(Level.DEBUG, runtime.explain("com.acme.Service").level());
            assertEquals(Level.INFO, runtime.explain("org.example.Service").level());

            runtime.setLevelOverride("com.acme.noisy", RuntimeLevelOverride.off());
            AtomicBoolean evaluated = new AtomicBoolean();
            noisy.atError().argumentLazy(() -> {
                evaluated.set(true);
                return "must-not-run";
            }).log("disabled {}");
            assertFalse(noisy.isErrorEnabled());
            assertFalse(evaluated.get());

            LogyardLogger future = runtime.logger("com.acme.Future");
            assertTrue(future.isDebugEnabled());
            assertSame(service, runtime.logger("com.acme.Service"));
        }
    }

    @Test
    void clearingOverridesRestoresTomlInheritance() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(sink, Level.INFO, Level.WARN))) {
            LogyardLogger logger = runtime.logger("com.acme.noisy.Client");
            runtime.setLevelOverride("com.acme", RuntimeLevelOverride.threshold(Level.DEBUG));
            runtime.setLevelOverride("com.acme.noisy", RuntimeLevelOverride.off());

            runtime.clearLevelOverride("com.acme.noisy");
            assertTrue(logger.isDebugEnabled());

            runtime.clearLevelOverride("com.acme");
            assertFalse(logger.isInfoEnabled());
            assertTrue(logger.isWarnEnabled());

            runtime.setLevelOverride("ROOT", RuntimeLevelOverride.threshold(Level.TRACE));
            assertTrue(runtime.logger("org.example.Service").isTraceEnabled());
            runtime.clearAllLevelOverrides();
            assertFalse(runtime.logger("org.example.Service").isDebugEnabled());
            assertTrue(runtime.levelOverrides().isEmpty());
        }
    }

    @Test
    void planReloadPreservesOverridesWhileUpdatingUnderlyingTomlLevels() {
        RecordingSink sink = new RecordingSink();
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(sink, Level.INFO, Level.WARN))) {
            LogyardLogger overridden = runtime.logger("com.acme.Service");
            LogyardLogger inherited = runtime.logger("org.example.Service");
            runtime.setLevelOverride("com.acme", RuntimeLevelOverride.threshold(Level.DEBUG));

            runtime.reload(plan(sink, Level.ERROR, Level.ERROR));

            assertTrue(overridden.isDebugEnabled());
            assertFalse(inherited.isWarnEnabled());
            assertTrue(inherited.isErrorEnabled());
            runtime.clearLevelOverride("com.acme");
            assertFalse(overridden.isWarnEnabled());
            assertTrue(overridden.isErrorEnabled());
        }
    }

    private static RuntimePlan plan(
            EventSink sink,
            Level root,
            Level acme) {
        return new RuntimePlan(
                RouteDefinition.root(root, List.of("capture"), List.of()),
                Map.of("com.acme", new RouteDefinition(acme, null, null)),
                Map.of("capture", sink),
                Map.of());
    }

    private static final class RecordingSink implements EventSink {
        private final List<LogEvent> events = new ArrayList<>();

        @Override
        public void accept(LogEvent event) {
            events.add(event);
        }
    }
}
