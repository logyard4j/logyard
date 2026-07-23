package com.zsumz.logyard.config.delivery;

import com.zsumz.logyard.api.Level;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded delivery policy inherited by every output. */
public record DeliveryConfig(
        String mode,
        int capacity,
        Map<Level, OverflowRuleConfig> overflow) {
    public static final int MIN_CAPACITY = 16;
    public static final int MAX_CAPACITY = 16_777_216;

    public DeliveryConfig {
        mode = Objects.requireNonNull(mode, "mode").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("async") && !mode.equals("sync")) {
            throw new IllegalArgumentException("delivery mode must be async or sync");
        }
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(
                    "delivery capacity must be between " + MIN_CAPACITY + " and " + MAX_CAPACITY);
        }
        overflow = Collections.unmodifiableMap(new EnumMap<>(overflow));
    }

    public boolean asynchronous() {
        return "async".equals(mode);
    }

    public DeliveryConfig withOverride(DeliveryOverrideConfig override) {
        if (override == null || override.isEmpty()) {
            return this;
        }
        return new DeliveryConfig(
                override.mode() == null ? mode : override.mode(),
                override.capacity() == null ? capacity : override.capacity(),
                overflow);
    }
}
