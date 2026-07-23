package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

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
