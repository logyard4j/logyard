package com.logyard4j.logyard.examples.opentelemetry;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("try")
public final class OpenTelemetryExampleApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenTelemetryExampleApplication.class);

    private OpenTelemetryExampleApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        // A fixed incoming identity makes this example reproducible; real instrumentation supplies the span.
        SpanContext parent = SpanContext.createFromRemoteParent("0123456789abcdef0123456789abcdef",
                "0123456789abcdef", TraceFlags.getSampled(), TraceState.getDefault());
        Context context = Context.root().with(Span.wrap(parent)).with(Baggage.builder()
                .put("tenant.id", "tenant-7").put("secret", "private-value").build());
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (Scope ignored = context.makeCurrent()) {
                LOGGER.info("trace direct");
                executor.submit(context.wrap(() -> LOGGER.info("trace wrapped"))).get(5, TimeUnit.SECONDS);
                executor.submit(() -> LOGGER.info("trace unwrapped")).get(5, TimeUnit.SECONDS);
            }
            LOGGER.info("trace outside");
        }
        LOGGER.info("trace shutdown flush");
    }
}
