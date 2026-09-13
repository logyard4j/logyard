package com.logyard4j.logyard.core.processing;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.processing.EventProcessor;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Bounded keyed token bucket. WARN and ERROR bypass rate limiting.
 * Evicted LRU bucket state is reused so key churn cannot mint a fresh burst. */
public final class RateLimitProcessor implements EventProcessor {
    private static final Set<String> FIXED_KEYS = Set.of("global", "logger", "event");

    private final double permitsPerNanosecond;
    private final int burst;
    private final String keyMode;
    private final int maxKeys;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);

    public RateLimitProcessor(
            double permitsPerSecond,
            int burst,
            String keyMode,
            int maxKeys) {
        this(permitsPerSecond, burst, keyMode, maxKeys, System::nanoTime);
    }

    RateLimitProcessor(
            double permitsPerSecond,
            int burst,
            String keyMode,
            int maxKeys,
            LongSupplier nanoTime) {
        if (!Double.isFinite(permitsPerSecond)
                || permitsPerSecond < 0.001d
                || permitsPerSecond > 1_000_000.0d) {
            throw new IllegalArgumentException("permits per second is outside the supported bound");
        }
        if (burst < 1 || burst > 1_000_000) {
            throw new IllegalArgumentException("burst is outside the supported bound");
        }
        if (maxKeys < 1 || maxKeys > 4_096) {
            throw new IllegalArgumentException("max keys is outside the supported bound");
        }
        this.permitsPerNanosecond = permitsPerSecond / 1_000_000_000.0d;
        this.burst = burst;
        this.keyMode = requireKeyMode(keyMode);
        this.maxKeys = "global".equals(this.keyMode) ? 1 : maxKeys;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public LogEvent process(LogEvent event) {
        Objects.requireNonNull(event, "event");
        if (event.level().ordinal() >= Level.WARN.ordinal()) {
            return event;
        }
        return acquire(key(event), nanoTime.getAsLong()) ? event : null;
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }

    private synchronized boolean acquire(String key, long now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                Iterator<Map.Entry<String, Bucket>> oldest = buckets.entrySet().iterator();
                if (!oldest.hasNext()) {
                    throw new IllegalStateException("rate-limit bucket catalog is unexpectedly empty");
                }
                Map.Entry<String, Bucket> evicted = oldest.next();
                bucket = evicted.getValue();
                oldest.remove();
            } else {
                bucket = new Bucket(burst, now);
            }
            buckets.put(key, bucket);
        }
        long elapsed = now - bucket.lastNanos;
        if (elapsed > 0) {
            bucket.tokens = Math.min(burst, bucket.tokens + elapsed * permitsPerNanosecond);
            bucket.lastNanos = now;
        } else if (elapsed < 0) {
            bucket.lastNanos = now;
        }
        if (bucket.tokens < 1.0d) {
            return false;
        }
        bucket.tokens -= 1.0d;
        return true;
    }

    private String key(LogEvent event) {
        return switch (keyMode) {
            case "global" -> "<global>";
            case "logger" -> event.loggerName();
            case "event" -> event.eventName() == null
                    ? Objects.toString(event.messageTemplate(), "<message>")
                    : event.eventName();
            default -> Objects.toString(
                    event.attributes().get(keyMode.substring("attribute:".length())),
                    "<missing>");
        };
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        private Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private static String requireKeyMode(String value) {
        String normalized = Objects.requireNonNull(value, "keyMode").trim();
        if (FIXED_KEYS.contains(normalized)) {
            return normalized;
        }
        String prefix = "attribute:";
        if (!normalized.startsWith(prefix)
                || normalized.length() <= prefix.length()
                || normalized.length() > prefix.length() + 256) {
            throw new IllegalArgumentException(
                    "rate-limit key must be global, logger, event, or attribute:NAME");
        }
        for (int index = prefix.length(); index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException("rate-limit attribute key contains a control character");
            }
        }
        return normalized;
    }
}
