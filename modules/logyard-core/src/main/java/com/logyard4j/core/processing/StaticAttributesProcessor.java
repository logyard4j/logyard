package com.logyard4j.core.processing;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.processing.EventProcessor;

import java.util.Objects;

public final class StaticAttributesProcessor implements EventProcessor {
    private final AttributeSet attributes;

    public StaticAttributesProcessor(AttributeSet attributes) {
        this.attributes = Objects.requireNonNull(attributes, "attributes");
    }

    @Override
    public LogEvent process(LogEvent event) {
        return event.withAttributes(attributes.mergedWith(event.attributes()));
    }
}
