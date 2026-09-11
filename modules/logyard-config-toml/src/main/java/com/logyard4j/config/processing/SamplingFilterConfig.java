package com.logyard4j.config.processing;

import com.logyard4j.config.validation.ConfigNames;
import java.util.Set;

/** Stateless deterministic sampling for trace, event, logger, attribute, or event-instance keys. */
public record SamplingFilterConfig(
        String name,
        double probability,
        String key,
        long seed) implements FilterConfig {
    private static final Set<String> FIXED_KEYS = Set.of("event-instance", "event", "trace", "logger");

    public SamplingFilterConfig {
        name = ConfigNames.component(name, "filter name");
        if (name.startsWith("logyard-")) {
            throw new IllegalArgumentException("filter names beginning with 'logyard-' are reserved");
        }
        if (!Double.isFinite(probability) || probability < 0.0d || probability > 1.0d) {
            throw new IllegalArgumentException("sampling probability must be between 0.0 and 1.0");
        }
        key = key == null ? "event-instance" : key.trim();
        if (!FIXED_KEYS.contains(key) && !validAttributeKey(key)) {
            throw new IllegalArgumentException(
                    "sampling key must be event-instance, event, trace, logger, or attribute:NAME");
        }
    }

    private static boolean validAttributeKey(String value) {
        return safeAttributeKey(value, "attribute:");
    }

    private static boolean safeAttributeKey(String value, String prefix) {
        if (!value.startsWith(prefix)
                || value.length() <= prefix.length()
                || value.length() > prefix.length() + 256) {
            return false;
        }
        for (int index = prefix.length(); index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
