package com.logyard4j.opentelemetry;

import com.logyard4j.api.Level;
import io.opentelemetry.api.logs.Severity;

/**
 * Maps Logyard levels onto OpenTelemetry severities.
 *
 * <p>{@link Level#severityNumber()} already publishes the OpenTelemetry severity numbers 1, 5, 9,
 * 13, and 17, so every level has one exact counterpart and the mapping never needs a fallback.</p>
 */
final class OtelSeverity {
    private OtelSeverity() {
    }

    /**
     * Returns the severity carrying the same severity number as the level.
     *
     * @param level Logyard event level
     * @return matching OpenTelemetry severity
     */
    static Severity of(Level level) {
        return switch (level) {
            case TRACE -> Severity.TRACE;
            case DEBUG -> Severity.DEBUG;
            case INFO -> Severity.INFO;
            case WARN -> Severity.WARN;
            case ERROR -> Severity.ERROR;
        };
    }
}
