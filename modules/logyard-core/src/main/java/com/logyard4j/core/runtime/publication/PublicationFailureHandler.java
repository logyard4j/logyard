package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.event.LogEvent;

/** Strategy for reporting an isolated capture, processor, or sink failure. */
@FunctionalInterface
interface PublicationFailureHandler {
    void handle(EventDraft draft, LogEvent event, Throwable failure);
}
