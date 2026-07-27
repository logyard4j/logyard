package com.zsumz.logyard.core.runtime.management;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.level.RuntimeLevelOverrides;
import com.zsumz.logyard.core.routing.RouteResolver;
import com.zsumz.logyard.core.runtime.RuntimeLoggerLevelSnapshot;
import com.zsumz.logyard.core.runtime.RuntimeManagementSnapshot;
import com.zsumz.logyard.core.runtime.RuntimePlan;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Builds point-in-time management views from one immutable runtime generation. */
public final class RuntimeManagementView {
    private RuntimeManagementView() {
    }

    public static Set<String> knownLoggerNames(
            Set<String> activeLoggerNames,
            RuntimePlan plan,
            RuntimeLevelOverrides overrides) {
        LinkedHashSet<String> names = new LinkedHashSet<>(activeLoggerNames);
        names.addAll(plan.configuredLevels().keySet());
        names.addAll(overrides.configuredLevels().keySet());
        return Set.copyOf(names);
    }

    public static RuntimeLoggerLevelSnapshot loggerLevelSnapshot(
            String loggerName,
            RuntimePlan plan,
            RuntimeLevelOverrides overrides) {
        Level effectiveBase = RouteResolver.resolve(loggerName, plan.root(), plan.loggers()).definition().level();
        return new RuntimeLoggerLevelSnapshot(
                plan.configuredLevels().get(loggerName),
                overrides.exactLevel(loggerName),
                Objects.requireNonNull(effectiveBase, "effective logger level"),
                overrides.resolve(loggerName));
    }

    public static RuntimeManagementSnapshot snapshot(
            Set<String> activeLoggerNames,
            RuntimePlan plan,
            RuntimeLevelOverrides overrides) {
        LinkedHashSet<String> names = new LinkedHashSet<>(activeLoggerNames);
        names.addAll(plan.loggers().keySet());
        names.addAll(plan.configuredLevels().keySet());
        names.addAll(overrides.configuredLevels().keySet());
        return new RuntimeManagementSnapshot(
                plan.root(),
                plan.loggers(),
                plan.configuredLevels(),
                overrides,
                names);
    }
}
