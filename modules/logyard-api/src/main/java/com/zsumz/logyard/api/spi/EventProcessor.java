package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

/** Transforms or drops one immutable event before output routing. */
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
