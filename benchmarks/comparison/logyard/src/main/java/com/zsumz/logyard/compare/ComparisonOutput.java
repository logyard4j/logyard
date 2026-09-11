package com.zsumz.logyard.compare;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.output.OutputProvider;
import com.zsumz.logyard.api.spi.output.OutputProviderContext;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Transfers the captured event to the same measured destination used by the incumbent appenders. */
public final class ComparisonOutput implements OutputProvider {
    static MeasuredDestination destination;

    public ComparisonOutput() {
    }

    @Override
    public String name() {
        return "comparison";
    }

    @Override
    public EventSink create(OutputProviderContext context, ProviderConfiguration configuration) {
        MeasuredDestination selected = destination;
        Function<LogEvent, byte[]> encoder = context.encoder() == null ? null
                : event -> (context.encoder().encode(event) + '\n').getBytes(StandardCharsets.UTF_8);
        return event -> {
            if (event.loggerName().equals("comparison")) {
                if (encoder == null) selected.accept(event.renderedMessage(), event.level().name(), event.attributes()::get);
                else selected.acceptEncoded(event.renderedMessage(), event, encoder);
            }
        };
    }
}
