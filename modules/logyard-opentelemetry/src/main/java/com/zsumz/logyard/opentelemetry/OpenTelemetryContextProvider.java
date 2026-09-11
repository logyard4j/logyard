package com.zsumz.logyard.opentelemetry;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.context.ContextProvider;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

import java.util.List;
import java.util.Objects;

/** Captures active OpenTelemetry identity and explicitly requested baggage on the publishing thread. */
@com.zsumz.logyard.api.annotation.InternalApi
public final class OpenTelemetryContextProvider implements ContextProvider {
    /** Creates the stateless ServiceLoader provider. */
    public OpenTelemetryContextProvider() {
    }

    @Override
    public String name() {
        return "opentelemetry";
    }

    @Override
    public AttributeSet capture(List<String> includedKeys) {
        Objects.requireNonNull(includedKeys, "includedKeys");
        if (includedKeys.size() > 129) throw new IllegalArgumentException("context request exceeds 129 keys");
        if (includedKeys.isEmpty()) return AttributeSet.EMPTY;
        Context current = Context.current();
        AttributeSet.Builder captured = null;
        if (includedKeys.contains("trace.*")) {
            SpanContext span = Span.fromContext(current).getSpanContext();
            if (span.isValid()) {
                captured = AttributeSet.builder()
                        .put("trace_id", span.getTraceId())
                        .put("span_id", span.getSpanId())
                        .put("trace_flags", span.getTraceFlags().asHex());
            }
        }
        Baggage baggage = null;
        for (String requested : includedKeys) {
            if (!requested.startsWith("baggage.") || requested.length() == "baggage.".length()) continue;
            if (baggage == null) baggage = Baggage.fromContext(current);
            String value = baggage.getEntryValue(requested.substring("baggage.".length()));
            if (value != null) {
                if (captured == null) captured = AttributeSet.builder();
                captured.put(requested, value);
            }
        }
        return captured == null ? AttributeSet.EMPTY : captured.build();
    }
}
