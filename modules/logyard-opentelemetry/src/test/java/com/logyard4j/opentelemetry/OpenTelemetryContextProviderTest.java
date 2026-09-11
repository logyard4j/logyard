package com.logyard4j.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.spi.context.ContextProvider;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class OpenTelemetryContextProviderTest {
    static final String TRACE = "0123456789abcdef0123456789abcdef";
    static final String SPAN = "0123456789abcdef";
    private final OpenTelemetryContextProvider provider = new OpenTelemetryContextProvider();

    @Test
    void discoversTheOptionalProvider() {
        assertTrue(ServiceLoader.load(ContextProvider.class).stream()
                .anyMatch(service -> service.type() == OpenTelemetryContextProvider.class));
    }

    @Test
    void capturesValidSampledAndUnsampledTraceIdentity() {
        for (boolean sampled : new boolean[] {false, true}) {
            try (Scope ignored = context(sampled).makeCurrent()) {
                AttributeSet captured = provider.capture(List.of("trace.*"));
                assertEquals(TRACE, captured.get("trace_id"));
                assertEquals(SPAN, captured.get("span_id"));
                assertEquals(sampled ? "01" : "00", captured.get("trace_flags"));
                assertEquals(3, captured.size());
            }
        }
    }

    @Test
    void omitsInvalidTraceIdentityAndHonorsDisabledCapture() {
        try (Scope ignored = Context.root().makeCurrent()) {
            assertSame(AttributeSet.EMPTY, provider.capture(List.of("trace.*")));
        }
        try (Scope ignored = context(true).makeCurrent()) {
            assertSame(AttributeSet.EMPTY, provider.capture(List.of()));
            assertNull(provider.capture(List.of("baggage.tenant.id")).get("trace_id"));
        }
    }

    @Test
    void capturesOnlyLiteralAllowlistedBaggageKeys() {
        AttributeSet captured;
        try (Scope ignored = context(true).makeCurrent()) {
            captured = provider.capture(List.of("baggage.tenant.id", "baggage.*", "baggage.missing"));
        }
        assertEquals("tenant-7", captured.get("baggage.tenant.id"));
        assertNull(captured.get("baggage.secret"));
        assertEquals(1, captured.size());
        assertFalse(captured.toMap().containsValue("private-value"));
    }

    @Test
    void boundsLargeBaggageValuesAndRejectsOversizedRequests() {
        Baggage large = Baggage.builder().put("payload", "x".repeat(1_000_000)).build();
        try (Scope ignored = Context.root().with(large).makeCurrent()) {
            String captured = (String) provider.capture(List.of("baggage.payload")).get("baggage.payload");
            assertTrue(captured.length() <= CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        }
        assertThrows(IllegalArgumentException.class, () -> provider.capture(Collections.nCopies(130, "trace.*")));
    }

    static Context context(boolean sampled) {
        SpanContext identity = SpanContext.createFromRemoteParent(TRACE, SPAN,
                sampled ? TraceFlags.getSampled() : TraceFlags.getDefault(), TraceState.getDefault());
        return Context.root().with(Span.wrap(identity)).with(Baggage.builder()
                .put("tenant.id", "tenant-7").put("secret", "private-value").build());
    }
}
