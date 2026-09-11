package com.logyard4j.opentelemetry;

import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerProvider;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded map from a Logyard logger name to its OpenTelemetry instrumentation-scope logger.
 *
 * <p>The OpenTelemetry log data model carries a logger name as the instrumentation scope of the
 * emitting {@link Logger}, so one scope logger per Logyard logger name is the faithful mapping.
 * Logger names are application-controlled and can be unbounded, so retention stops at
 * {@link #MAX_SCOPES} distinct scopes and every later name shares {@link #FALLBACK_SCOPE}. The
 * {@code logger.name} attribute is written on every record, so a saturated cache costs scope
 * fidelity but never the logger name itself.</p>
 */
final class ScopeLoggerCache {
    /** Maximum distinct instrumentation scopes retained for one sink. */
    static final int MAX_SCOPES = 1_024;

    /** Instrumentation scope shared once the per-logger scope allowance is exhausted. */
    static final String FALLBACK_SCOPE = "com.logyard4j.opentelemetry";

    private final ConcurrentHashMap<String, Logger> scopes = new ConcurrentHashMap<>();
    private final LoggerProvider loggerProvider;
    private final Logger fallback;
    private final int maxScopes;

    ScopeLoggerCache(LoggerProvider loggerProvider, int maxScopes) {
        this.loggerProvider = Objects.requireNonNull(loggerProvider, "loggerProvider");
        if (maxScopes < 1 || maxScopes > MAX_SCOPES) {
            throw new IllegalArgumentException("scope logger capacity must be 1 to " + MAX_SCOPES);
        }
        this.maxScopes = maxScopes;
        this.fallback = Objects.requireNonNull(
                loggerProvider.get(FALLBACK_SCOPE), "fallback scope logger");
    }

    /**
     * Returns the scope logger for one Logyard logger name.
     *
     * @param loggerName Logyard logger name, or {@code null}
     * @return retained scope logger, or the shared fallback scope logger
     */
    Logger loggerFor(String loggerName) {
        if (loggerName == null || loggerName.isEmpty()) {
            return fallback;
        }
        Logger retained = scopes.get(loggerName);
        if (retained != null) {
            return retained;
        }
        synchronized (scopes) {
            retained = scopes.get(loggerName);
            if (retained != null) return retained;
            if (scopes.size() >= maxScopes) return fallback;
            Logger created = Objects.requireNonNull(loggerProvider.get(loggerName), "scope logger");
            scopes.put(loggerName, created);
            return created;
        }
    }

    /**
     * Returns the number of retained scope loggers.
     *
     * @return retained scope logger count
     */
    int size() {
        return scopes.size();
    }

    /** Releases every retained scope logger without touching the application-owned provider. */
    void clear() {
        synchronized (scopes) {
            scopes.clear();
        }
    }
}
