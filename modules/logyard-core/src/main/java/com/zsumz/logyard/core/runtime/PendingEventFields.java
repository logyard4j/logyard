package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.AttributeSet;

/** Fluent-builder arguments and attributes awaiting capture inside the publication pipeline. */
final class PendingEventFields {
    private final PendingArguments arguments;
    private final PendingAttributes attributes;

    PendingEventFields(PendingArguments arguments, PendingAttributes attributes) {
        this.arguments = arguments;
        this.attributes = attributes;
    }

    CapturedEventFields capture() {
        return new CapturedEventFields(arguments.capture(), attributes.capture(arguments.omitted()));
    }

    record CapturedEventFields(Object[] arguments, AttributeSet attributes) {
    }
}
