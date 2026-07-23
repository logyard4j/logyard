package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.LogEvent;

/** Strategy for reporting an isolated capture, processor, or sink failure. */
@FunctionalInterface
interface PublicationFailureHandler {
    void handle(EventDraft draft, LogEvent event, Throwable failure);
}
