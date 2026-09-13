package com.logyard4j.logyard.core.delivery.async;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.delivery.OverflowAction;

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
        rules.put(Level.WARN, new Rule(OverflowAction.WAIT_DROP, Duration.ofMillis(2)));
        rules.put(Level.ERROR, new Rule(OverflowAction.WAIT_DROP, Duration.ofMillis(20)));
        if (configured != null) {
            rules.putAll(configured);
        }
    }

    public Rule ruleFor(Level level) {
        return rules.get(level);
    }

    boolean dropsUndelivered(Level level) {
        OverflowAction action = ruleFor(level).action();
        return action == OverflowAction.DROP || action == OverflowAction.WAIT_DROP;
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
