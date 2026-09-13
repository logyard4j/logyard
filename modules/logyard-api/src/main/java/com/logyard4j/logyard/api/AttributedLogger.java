package com.logyard4j.logyard.api;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.ingress.IngressMetadata;

import java.util.Objects;

/** Logger presetting attributes on every event it publishes, each replaced by a colliding event attribute. */
final class AttributedLogger implements LogyardLogger {
    private final LogyardLogger delegate;
    private final AttributeSet preset;

    private AttributedLogger(LogyardLogger delegate, AttributeSet preset) {
        this.delegate = delegate;
        this.preset = preset;
    }

    static LogyardLogger of(LogyardLogger logger, AttributeSet attributes) {
        Objects.requireNonNull(attributes, "attributes");
        // Presets merge here so repeated currying stays one wrapper deep instead of a delegate chain.
        return logger instanceof AttributedLogger attributed
                ? new AttributedLogger(attributed.delegate, attributed.preset.mergedWith(attributes))
                : new AttributedLogger(logger, attributes);
    }

    @Override public String name() { return delegate.name(); }
    @Override public boolean isEnabled(Level level) { return delegate.isEnabled(level); }

    @Override
    public LogBuilder at(Level level) {
        // A disabled level yields a no-op builder, so presets cost nothing when nothing is published.
        return delegate.at(level).addAll(preset);
    }

    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable) {
        if (delegate.isEnabled(level)) {
            delegate.log(level, eventName, messageTemplate, arguments, merged(attributes), throwable);
        }
    }

    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        if (delegate.isEnabled(level)) {
            delegate.log(level, eventName, messageTemplate, arguments, merged(attributes), throwable, metadata);
        }
    }

    private AttributeSet merged(AttributeSet attributes) {
        return attributes == null ? preset : preset.mergedWith(attributes);
    }
}
