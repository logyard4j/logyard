package com.logyard4j.logyard.runtime.management;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.core.routing.RouteDefinition;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.core.runtime.RuntimePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LoggerManagementSnapshotTest {
    @Test
    void pointAndListingSnapshotsAgreeAcrossBaseInheritedAndOperationalLevels() {
        try (DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(plan(Level.DEBUG))) {
            LoggerLevelManagement levels = LoggerLevelManagement.forRuntime(runtime);
            runtime.logger("app.child.Repository");
            runtime.logger("other");
            assertViewsAgree(levels);

            levels.setLevel("ROOT", LoggerLevel.ERROR);
            levels.setLevel("app", LoggerLevel.OFF);
            assertViewsAgree(levels);
            LoggerLevelSnapshot inheritedOverride = levels.getLoggerLevel("app.child.Repository");
            assertNull(inheritedOverride.runtimeOverride());
            assertEquals(LoggerLevel.OFF, inheritedOverride.effectiveLevel());
            assertEquals(LoggerLevelOrigin.RUNTIME_OVERRIDE, inheritedOverride.origin());

            levels.setLevel("app.child", LoggerLevel.TRACE);
            assertViewsAgree(levels);
            LoggerLevelSnapshot beforeReload = levels.getLoggerLevel("app");
            runtime.reload(plan(Level.WARN));
            assertViewsAgree(levels);
            assertEquals(LoggerLevel.DEBUG, beforeReload.baseConfiguredLevel());
            assertEquals(LoggerLevel.WARN, levels.getLoggerLevel("app").baseConfiguredLevel());

            levels.clearLevel("app.child");
            assertViewsAgree(levels);
            levels.clearAllOverrides();
            assertViewsAgree(levels);
            assertEquals(LoggerLevel.WARN, levels.getEffectiveLevel("app.child.Repository"));
        }
    }

    private static void assertViewsAgree(LoggerLevelManagement levels) {
        for (Map.Entry<String, LoggerLevelSnapshot> entry : levels.listLoggerLevels().entrySet()) {
            String name = entry.getKey();
            assertEquals(entry.getValue(), levels.getLoggerLevel(name), name);
            assertEquals(entry.getValue().effectiveLevel(), levels.getEffectiveLevel(name), name);
        }
    }

    private static RuntimePlan plan(Level applicationLevel) {
        return new RuntimePlan(RouteDefinition.root(Level.INFO, List.of(), List.of()),
                Map.of("app", new RouteDefinition(applicationLevel, null, null),
                        "app.child", new RouteDefinition(null, List.of(), null)),
                Map.of(), Map.of());
    }
}
