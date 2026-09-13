package com.logyard4j.logyard.config;

import com.logyard4j.logyard.config.delivery.DeliveryConfig;
import com.logyard4j.logyard.config.encoding.EncoderConfig;
import com.logyard4j.logyard.config.encoding.JsonProfileConfig;
import com.logyard4j.logyard.config.formatting.FormatterConfig;
import com.logyard4j.logyard.config.logging.LoggerRuleConfig;
import com.logyard4j.logyard.config.output.OutputConfig;
import com.logyard4j.logyard.config.processing.EnricherConfig;
import com.logyard4j.logyard.config.processing.FilterConfig;
import com.logyard4j.logyard.config.runtime.ContextConfig;
import com.logyard4j.logyard.config.runtime.ResourceConfig;
import com.logyard4j.logyard.config.runtime.RuntimeConfig;
import com.logyard4j.logyard.config.runtime.ServiceConfig;
import com.logyard4j.logyard.config.theme.ThemeConfig;
import com.logyard4j.logyard.config.validation.aggregate.LogyardConfigValidator;

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
