package com.logyard4j.logyard.core.runtime.publication;

import com.logyard4j.logyard.api.event.LogEvent;

/** Strategy for reporting an isolated capture, processor, or sink failure. */
@FunctionalInterface
interface PublicationFailureHandler {
    void handle(EventDraft draft, LogEvent event, Throwable failure);
}
