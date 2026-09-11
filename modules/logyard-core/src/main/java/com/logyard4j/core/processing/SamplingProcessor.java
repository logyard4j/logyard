package com.logyard4j.core.processing;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.processing.EventProcessor;

import java.util.Objects;
import java.util.Set;

/** Stateless deterministic sampler. WARN and ERROR are never sampled out. */
public final class SamplingProcessor implements EventProcessor {
    private static final Set<String> FIXED_KEYS = Set.of(
            "event-instance", "event", "trace", "logger");

    private final double probability;
    private final String key;
    private final long seed;

    public SamplingProcessor(double probability, String key, long seed) {
        if (!Double.isFinite(probability) || probability < 0.0d || probability > 1.0d) {
            throw new IllegalArgumentException("sampling probability must be between 0.0 and 1.0");
        }
        this.probability = probability;
        this.key = requireKey(key);
        this.seed = seed;
    }

    @Override
    public LogEvent process(LogEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.level().ordinal() >= Level.WARN.ordinal() || probability >= 1.0d) {
            return event;
        }
        if (probability <= 0.0d) {
            return null;
        }
        long hash = mix(identityHash(event) ^ seed);
        double unit = (hash >>> 11) * 0x1.0p-53;
        return unit < probability ? event : null;
    }

    private long identityHash(LogEvent event) {
        return switch (key) {
            case "event" -> stableHash(eventIdentity(event));
            case "trace" -> attributeOrInstance(event, "trace_id");
            case "logger" -> stableHash(event.loggerName());
            case "event-instance" -> instanceHash(event);
            default -> attributeOrInstance(event, key.substring("attribute:".length()));
        };
    }

    private static long attributeOrInstance(LogEvent event, String attribute) {
        Object value = event.attributes().get(attribute);
        return value == null ? instanceHash(event) : stableHash(String.valueOf(value));
    }

    private static long instanceHash(LogEvent event) {
        long result = stableHash(eventIdentity(event));
        result = mix(result ^ event.timestampMillis());
        result = mix(result ^ event.observedTimestampUnixNanos());
        return mix(result ^ event.threadId());
    }

    private static String eventIdentity(LogEvent event) {
        if (event.eventName() != null && !event.eventName().isBlank()) {
            return event.loggerName() + '\u0000' + event.eventName();
        }
        return event.loggerName() + '\u0000' + Objects.toString(event.messageTemplate(), "");
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long value) {
        long mixed = value;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return mixed;
    }

    private static String requireKey(String value) {
        String normalized = Objects.requireNonNull(value, "key").trim();
        if (FIXED_KEYS.contains(normalized)) {
            return normalized;
        }
        String prefix = "attribute:";
        if (!normalized.startsWith(prefix)
                || normalized.length() <= prefix.length()
                || normalized.length() > prefix.length() + 256) {
            throw new IllegalArgumentException(
                    "sampling key must be event-instance, event, trace, logger, or attribute:NAME");
        }
        for (int index = prefix.length(); index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException("sampling attribute key contains a control character");
            }
        }
        return normalized;
    }
}
