package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class OverflowPolicy {
    private final EnumMap<Level, Rule> rules = new EnumMap<>(Level.class);

    public OverflowPolicy(Map<Level, Rule> configured) {
        rules.put(Level.TRACE, new Rule(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.DEBUG, new Rule(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.INFO, new Rule(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.WARN, new Rule(OverflowAction.STDERR, Duration.ofMillis(2)));
        rules.put(Level.ERROR, new Rule(OverflowAction.STDERR, Duration.ZERO));
        if (configured != null) {
            rules.putAll(configured);
        }
    }

    public Rule ruleFor(Level level) {
        return rules.get(level);
    }

    public record Rule(OverflowAction action, Duration waitDuration) {
        public Rule {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(waitDuration, "waitDuration");
            if (waitDuration.isNegative()) {
                throw new IllegalArgumentException("overflow wait duration must not be negative");
            }
        }
    }
}
