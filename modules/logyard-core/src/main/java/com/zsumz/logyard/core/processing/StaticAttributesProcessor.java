package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventProcessor;

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
