package com.logyard4j.core.delivery;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.ExceptionSnapshot;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.api.spi.diagnostics.HealthContributor;
import com.logyard4j.core.diagnostics.EmergencyText;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FilteringSink implements EventSink, HealthContributor {
    private final Level minimum;
    private final EventSink delegate;

    public FilteringSink(Level minimum, EventSink delegate) {
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void accept(LogEvent event) {
        if (minimum.enables(event.level())) {
            delegate.accept(event);
        }
    }

    @Override
    public void flush() { delegate.flush(); }

    @Override
    public void close() { delegate.close(); }

    @Override
    public ComponentHealth health(String componentName) {
        ComponentHealth current = delegate instanceof HealthContributor contributor
                ? contributor.health(componentName)
                : ComponentHealth.healthy(componentName, "output");
        Map<String, String> details = new LinkedHashMap<>(current.details());
        details.put("minimum_level", minimum.name().toLowerCase(java.util.Locale.ROOT));
        return new ComponentHealth(
                current.name(),
                current.kind(),
                current.status(),
                details,
                current.metrics());
    }
}
