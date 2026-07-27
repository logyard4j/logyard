package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.level.RuntimeLevelOverride;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.routing.RouteResolver;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One internally consistent immutable view of logger-management state. */
public final class RuntimeManagementSnapshot {
    private final RouteDefinition root;
    private final Map<String, RouteDefinition> loggerRules;
    private final Map<String, Level> baseConfiguredLevels;
    private final RuntimeLevelOverrides levelOverrides;
    private final Set<String> knownLoggerNames;

    public RuntimeManagementSnapshot(
            RouteDefinition root,
            Map<String, RouteDefinition> loggerRules,
            Map<String, Level> baseConfiguredLevels,
            RuntimeLevelOverrides levelOverrides,
            Set<String> knownLoggerNames) {
        this.root = Objects.requireNonNull(root, "root");
        this.loggerRules = Objects.requireNonNull(loggerRules, "loggerRules");
        this.baseConfiguredLevels = Objects.requireNonNull(baseConfiguredLevels, "baseConfiguredLevels");
        this.levelOverrides = Objects.requireNonNull(levelOverrides, "levelOverrides");
        this.knownLoggerNames = Set.copyOf(Objects.requireNonNull(knownLoggerNames, "knownLoggerNames"));
    }

    public Map<String, Level> baseConfiguredLevels() {
        return baseConfiguredLevels;
    }

    public Map<String, RuntimeLevelOverride> levelOverrides() {
        return levelOverrides.configuredLevels();
    }

    public Set<String> knownLoggerNames() {
        return knownLoggerNames;
    }

    public Level effectiveBaseLevel(String loggerName) {
        return Objects.requireNonNull(
                RouteResolver.resolve(loggerName, root, loggerRules).definition().level(),
                "effective logger level");
    }

    public RuntimeLevelOverride effectiveLevelOverride(String loggerName) {
        return levelOverrides.resolve(loggerName);
    }
}
