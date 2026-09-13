package com.logyard4j.logyard.api.spi.processing;

import com.logyard4j.logyard.api.event.LogEvent;

/**
 * Transforms or drops one immutable event before output routing.
 *
 * <p>One processor instance belongs to one immutable runtime plan and may be invoked concurrently by
 * unrelated publication threads. Implementations must be thread-safe, must keep work bounded, and
 * should not log recursively. Processors have no managed close callback, so they must not own
 * resources that require lifecycle cleanup or retain application objects after an invocation.</p>
 */
@FunctionalInterface
public interface EventProcessor {
    /**
     * Transforms or drops one event.
     *
     * @param event event to process
     * @return transformed event, the original event, or {@code null} to drop it
     */
    LogEvent process(LogEvent event);
}
