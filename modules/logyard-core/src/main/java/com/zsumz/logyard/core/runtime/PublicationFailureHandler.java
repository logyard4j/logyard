package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.LogEvent;

/** Strategy for reporting a failure after an event has been captured. */
@FunctionalInterface
interface PublicationFailureHandler {
    void handle(LogEvent event, RuntimeException failure);
}
