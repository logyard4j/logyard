package com.zsumz.logyard.config;

import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.encoding.EncoderConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.logging.LoggerRuleConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.processing.EnricherConfig;
import com.zsumz.logyard.config.processing.FilterConfig;
import com.zsumz.logyard.config.runtime.ContextConfig;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.config.runtime.RuntimeConfig;
import com.zsumz.logyard.config.runtime.ServiceConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;
import com.zsumz.logyard.config.validation.aggregate.LogyardConfigValidator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable, schema-versioned Logyard configuration. */
public record LogyardConfig(
        int schema,
        ServiceConfig service,
        ResourceConfig resource,
        RuntimeConfig runtime,
        ContextConfig context,
        LoggerRuleConfig rootLogger,
        Map<String, LoggerRuleConfig> loggers,
        DeliveryConfig delivery,
        Map<String, OutputConfig> outputs,
        Map<String, ThemeConfig> themes,
        Map<String, FormatterConfig> formatters,
        Map<String, EncoderConfig> encoders,
        Map<String, JsonProfileConfig> jsonProfiles,
        Map<String, EnricherConfig> enrichers,
        Map<String, FilterConfig> filters) {
    public static final long MAX_AGGREGATE_DELIVERY_SLOTS = 16_777_216L;

    public LogyardConfig {
        if (schema != 1) {
            throw new IllegalArgumentException("schema must be 1");
        }
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rootLogger, "rootLogger");
        Objects.requireNonNull(delivery, "delivery");
        loggers = immutable(loggers);
        outputs = immutable(outputs);
        themes = immutable(themes);
        formatters = immutable(formatters);
        encoders = immutable(encoders);
        jsonProfiles = immutable(jsonProfiles);
        enrichers = immutable(enrichers);
        filters = immutable(filters);
        LogyardConfigValidator.validate(
                rootLogger, loggers, delivery, outputs, formatters, encoders, jsonProfiles, enrichers, filters,
                MAX_AGGREGATE_DELIVERY_SLOTS);
    }

    public DeliveryConfig deliveryFor(OutputConfig output) {
        return delivery.withOverride(output.delivery());
    }

    public long aggregateDeliverySlots() {
        return LogyardConfigValidator.aggregateDeliverySlots(delivery, outputs);
    }

    public boolean hasJsonProfile(String name) {
        return LogyardConfigValidator.isBuiltInJsonProfile(name) || jsonProfiles.containsKey(name);
    }

    private static <T> Map<String, T> immutable(Map<String, T> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }
}
