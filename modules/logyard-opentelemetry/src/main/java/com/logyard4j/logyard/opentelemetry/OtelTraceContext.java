package com.logyard4j.logyard.opentelemetry;

import com.logyard4j.logyard.api.event.AttributeSet;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

/** Restores only captured caller context, excluding any ambient delivery-thread span. */
final class OtelTraceContext {
    private OtelTraceContext() {
    }

    /**
     * Applies captured trace identity, if any, to a record builder.
     *
     * @param builder record builder being assembled
     * @param attributes bounded event attributes
     */
    static void apply(LogRecordBuilder builder, AttributeSet attributes) {
        builder.setContext(Context.root());
        if (!(attributes.get("trace_id") instanceof String traceId)
                || !(attributes.get("span_id") instanceof String spanId)) {
            return;
        }
        if (!TraceId.isValid(traceId) || !SpanId.isValid(spanId)) {
            return;
        }
        TraceFlags flags = flags(attributes.get("trace_flags"));
        SpanContext captured = SpanContext.create(traceId, spanId, flags, TraceState.getDefault());
        builder.setContext(Context.root().with(Span.wrap(captured)));
    }
    private static TraceFlags flags(Object value) {
        if (!(value instanceof String text) || text.length() != 2) return TraceFlags.getDefault();
        int high = Character.digit(text.charAt(0), 16);
        int low = Character.digit(text.charAt(1), 16);
        return high < 0 || low < 0 ? TraceFlags.getDefault() : TraceFlags.fromByte((byte) (high * 16 + low));
    }
}
