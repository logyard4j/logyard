package com.logyard4j.core.level;

import com.logyard4j.api.Level;

import java.util.Objects;

/** One runtime threshold override, including the disabled state not represented by event levels. */
public final class RuntimeLevelOverride {
    private static final RuntimeLevelOverride OFF = new RuntimeLevelOverride(null);

    private final Level threshold;

    private RuntimeLevelOverride(Level threshold) {
        this.threshold = threshold;
    }

    public static RuntimeLevelOverride threshold(Level threshold) {
        return new RuntimeLevelOverride(Objects.requireNonNull(threshold, "threshold"));
    }

    public static RuntimeLevelOverride off() {
        return OFF;
    }

    public boolean disabled() {
        return threshold == null;
    }

    public Level threshold() {
        if (disabled()) {
            throw new IllegalStateException("disabled runtime level override has no threshold");
        }
        return threshold;
    }

    public int enabledMask() {
        return disabled() ? 0 : Level.enabledMaskFrom(threshold);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuntimeLevelOverride override && threshold == override.threshold;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(threshold);
    }

    @Override
    public String toString() {
        return disabled() ? "OFF" : threshold.name();
    }
}
