package com.zsumz.logyard.opentelemetry;

import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.output.OutputProvider;
import com.zsumz.logyard.api.spi.output.OutputProviderContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.logs.LoggerProvider;

import java.util.Objects;

/** ServiceLoader output forwarding records to the explicitly installed Logs API bridge. */
@com.zsumz.logyard.api.annotation.InternalApi
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
