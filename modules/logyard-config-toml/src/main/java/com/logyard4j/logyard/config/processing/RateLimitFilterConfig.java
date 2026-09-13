package com.logyard4j.logyard.config.processing;

import com.logyard4j.logyard.config.validation.ConfigNames;
import java.util.Set;

/** Bounded token-bucket rate limit with finite key cardinality. */
public record RateLimitFilterConfig(
        String name,
        double permitsPerSecond,
        int burst,
        String key,
        int maxKeys) implements FilterConfig {
    private static final Set<String> FIXED_KEYS = Set.of("global", "logger", "event");

    public RateLimitFilterConfig {
        name = ConfigNames.component(name, "filter name");
        if (name.startsWith("logyard-")) {
            throw new IllegalArgumentException("filter names beginning with 'logyard-' are reserved");
        }
        if (!Double.isFinite(permitsPerSecond)
                || permitsPerSecond < 0.001d
                || permitsPerSecond > 1_000_000.0d) {
            throw new IllegalArgumentException(
                    "permits_per_second must be between 0.001 and 1000000");
        }
        if (burst < 1 || burst > 1_000_000) {
            throw new IllegalArgumentException("rate-limit burst must be between 1 and 1000000");
        }
        key = key == null ? "logger" : key.trim();
        if (!FIXED_KEYS.contains(key) && !validAttributeKey(key)) {
            throw new IllegalArgumentException(
                    "rate-limit key must be global, logger, event, or attribute:NAME");
        }
        if (maxKeys < 1 || maxKeys > 4_096) {
            throw new IllegalArgumentException("rate-limit max_keys must be between 1 and 4096");
        }
        if ("global".equals(key)) {
            maxKeys = 1;
        }
    }

    private static boolean validAttributeKey(String value) {
        String prefix = "attribute:";
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
