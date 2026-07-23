package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

/**
 * Converts one event to human-readable text.
 *
 * <p>Logyard wraps provider-created formatters with runtime guardrails: {@code null}
 * results fail the owning output, text is bounded to the event capture limit, and
 * control characters are escaped so the result remains one physical line. A
 * formatter instance belongs to one immutable runtime plan and should not retain
 * application objects beyond an invocation.</p>
 */
@FunctionalInterface
public interface TextFormatter {
    String format(LogEvent event);
}
