package com.logyard4j.logyard.config.delivery;

import java.util.Locale;

/** Optional per-output override of the inherited bounded delivery policy. */
public record DeliveryOverrideConfig(String mode, Integer capacity) {
    public static final DeliveryOverrideConfig INHERIT = new DeliveryOverrideConfig(null, null);

    public DeliveryOverrideConfig {
        if (mode != null) {
            mode = mode.trim().toLowerCase(Locale.ROOT);
            if (!mode.equals("async") && !mode.equals("sync")) {
                throw new IllegalArgumentException("delivery mode must be async or sync");
            }
        }
        if (capacity != null
                && (capacity < DeliveryConfig.MIN_CAPACITY || capacity > DeliveryConfig.MAX_CAPACITY)) {
            throw new IllegalArgumentException(
                    "delivery capacity must be between " + DeliveryConfig.MIN_CAPACITY
                            + " and " + DeliveryConfig.MAX_CAPACITY);
        }
    }

    public boolean isEmpty() {
        return mode == null && capacity == null;
    }
}
