package com.logyard4j.api.spi.formatting;

import com.logyard4j.api.event.LogEvent;

/**
 * Converts one event to human-readable text.
 *
 * <p>Logyard wraps provider-created formatters with runtime guardrails: {@code null}
 * results fail the owning output, text is bounded to the event capture limit, and
 * control characters are escaped so the result remains one physical line. A
 * formatter instance belongs to one immutable runtime plan, may be invoked concurrently,
 * and must be thread-safe. Implementations should also avoid assumptions that prohibit
 * reentrant logging callbacks. A formatter has no managed close callback, so it must not
 * own resources that require lifecycle cleanup or retain application objects beyond an invocation.</p>
 */
@FunctionalInterface
public interface TextFormatter {
    /**
     * Formats one event.
     *
     * @param event event to format
     * @return bounded one-line text
     */
    String format(LogEvent event);
}
