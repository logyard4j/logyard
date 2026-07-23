package com.zsumz.logyard.config;

import com.zsumz.logyard.api.delivery.OverflowAction;
import java.time.Duration;
import java.util.Objects;

public record OverflowRuleConfig(OverflowAction action, Duration after) {
    public OverflowRuleConfig {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(after, "after");
    }
}
