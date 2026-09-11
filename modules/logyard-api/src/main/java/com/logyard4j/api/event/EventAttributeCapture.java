package com.logyard4j.api.event;

import java.util.Objects;

/** Reapplies the event's attribute allowance after processors replace or enrich attributes. */
final class EventAttributeCapture {
    private static final String TRUNCATED_KEY = SystemAttributes.CAPTURE_TRUNCATED;

    private EventAttributeCapture() {
    }

    static Result capture(
            AttributeSet replacement,
            CaptureAllowance allowance,
            boolean inheritedTruncation) {
        Objects.requireNonNull(replacement, "replacement");
        CaptureContext context = CaptureContext.forAttributes(allowance);
        return CaptureContext.within(context, () -> {
            AttributeSet captured = replacement.recapture(context);
            boolean truncated = inheritedTruncation
                    || context.truncated()
                    || Boolean.TRUE.equals(captured.get(TRUNCATED_KEY));
            if (truncated) {
                captured = captured.withSystemAttribute(TRUNCATED_KEY, true);
            }
            return new Result(captured, context.remainingEntries(), truncated);
        });
    }

    record Result(AttributeSet attributes, int remainingTraversalEntries, boolean truncated) {
    }
}
