package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

@FunctionalInterface
public interface EventProcessor {
    /** Returns the transformed event, or {@code null} to drop it. */
    LogEvent process(LogEvent event);
}
