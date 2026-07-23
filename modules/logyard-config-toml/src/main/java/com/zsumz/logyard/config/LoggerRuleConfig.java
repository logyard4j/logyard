package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;

import java.util.List;

/** One hierarchical logger rule with optional inherited fields. */
public record LoggerRuleConfig(
        Level level,
        List<String> outputs,
        List<String> enrich,
        List<String> filters) {
    public LoggerRuleConfig {
        outputs = ConfigNames.uniqueReferences(outputs, "outputs", true);
        enrich = ConfigNames.uniqueReferences(enrich, "enrich", true);
        filters = ConfigNames.uniqueReferences(filters, "filters", true);
    }

    public LoggerRuleConfig(Level level, List<String> outputs, List<String> enrich) {
        this(level, outputs, enrich, null);
    }
}
