package com.logyard4j.opentelemetry;

import com.logyard4j.api.spi.config.ProviderConfiguration;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.api.spi.output.OutputProvider;
import com.logyard4j.api.spi.output.OutputProviderContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LoggerProvider;

import java.util.Objects;

/** ServiceLoader output forwarding records to the explicitly installed Logs API bridge. */
@com.logyard4j.api.annotation.InternalApi
public final class OtelOutputProvider implements OutputProvider {
    /** Creates a provider instance. */
    public OtelOutputProvider() {
        // Discovery may construct candidates that are never published, so creation stays inert.
    }

    @Override
    public String name() {
        return "otel";
    }

    @Override
    public EventSink create(OutputProviderContext context, ProviderConfiguration configuration) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.values().isEmpty()) {
            throw new IllegalArgumentException("otel output accepts no provider configuration keys");
        }
        if (context.encoder() != null || context.formatter() != null) {
            throw new IllegalArgumentException("otel output accepts neither an encoder nor a formatter");
        }
        return new OtelLogRecordSink(logsBridge());
    }

    private static LoggerProvider logsBridge() {
        OpenTelemetry installed = LogyardOpenTelemetry.installedOrNull();
        if (installed == null) {
            throw new IllegalStateException("otel output requires LogyardOpenTelemetry.install(applicationSdk) before startup");
        }
        return installed.getLogsBridge();
    }
}
