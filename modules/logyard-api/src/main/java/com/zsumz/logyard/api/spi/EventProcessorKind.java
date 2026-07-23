package com.zsumz.logyard.api.spi;

/** Declared role of a provider-created event processor. */
public enum EventProcessorKind {
    /** Adds or replaces event data without dropping the event. */
    ENRICHER,

    /** Accepts or drops an event. */
    FILTER
}
