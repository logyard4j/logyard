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

    Object[] captureArguments() {
        return arguments.capture();
    }

    AttributeSet captureAttributes() {
        return attributes.capture();
    }

    int suppliedArgumentCount() {
        return arguments.suppliedCount();
    }
}
