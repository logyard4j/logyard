package com.zsumz.logyard.config;

import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.encoding.EncoderConfig;
import com.zsumz.logyard.config.encoding.JsonEncoderConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.logging.LoggerRuleConfig;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;
import com.zsumz.logyard.config.output.JsonStreamOutputConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.processing.EnricherConfig;
import com.zsumz.logyard.config.processing.FilterConfig;
import com.zsumz.logyard.config.runtime.ContextConfig;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.config.runtime.RuntimeConfig;
import com.zsumz.logyard.config.runtime.ServiceConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private static final Set<String> BUILT_IN_JSON_PROFILES = Set.of("logyard", "ecs", "compact");

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
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("at least one output is required");
        }
        validateProcessorNames(enrichers, filters);
        validateLogger("root", rootLogger, outputs, enrichers, filters);
        for (Map.Entry<String, LoggerRuleConfig> entry : loggers.entrySet()) {
            validateLogger(entry.getKey(), entry.getValue(), outputs, enrichers, filters);
        }
        validateEncoders(encoders, jsonProfiles);
        for (OutputConfig output : outputs.values()) {
            validateOutput(output, formatters, encoders);
            DeliveryConfig effective = delivery.withOverride(output.delivery());
            if (output instanceof CustomOutputConfig && !effective.asynchronous()) {
                throw new IllegalArgumentException("custom output '" + output.name()
                        + "' must use asynchronous delivery");
            }
        }
        long slots = aggregateDeliverySlots(delivery, outputs);
        if (slots > MAX_AGGREGATE_DELIVERY_SLOTS) {
            throw new IllegalArgumentException(
                    "aggregate delivery capacity exceeds " + MAX_AGGREGATE_DELIVERY_SLOTS
                            + " event slots: " + slots);
        }
    }

    public DeliveryConfig deliveryFor(OutputConfig output) {
        return delivery.withOverride(output.delivery());
    }

    public long aggregateDeliverySlots() {
        return aggregateDeliverySlots(delivery, outputs);
    }

    public boolean hasJsonProfile(String name) {
        return BUILT_IN_JSON_PROFILES.contains(name) || jsonProfiles.containsKey(name);
    }

    private static <T> Map<String, T> immutable(Map<String, T> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }

    private static void validateProcessorNames(
            Map<String, EnricherConfig> enrichers,
            Map<String, FilterConfig> filters) {
        LinkedHashSet<String> duplicates = new LinkedHashSet<>(enrichers.keySet());
        duplicates.retainAll(filters.keySet());
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException(
                    "enricher and filter names must be distinct: " + duplicates);
        }
    }

    private static void validateLogger(
            String logger,
            LoggerRuleConfig rule,
            Map<String, OutputConfig> outputs,
            Map<String, EnricherConfig> enrichers,
            Map<String, FilterConfig> filters) {
        if (rule.outputs() != null) {
            for (String name : rule.outputs()) {
                if (!outputs.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "logger '" + logger + "' references unknown output '" + name + "'");
                }
            }
        }
        if (rule.enrich() != null) {
            for (String name : rule.enrich()) {
                if (!enrichers.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "logger '" + logger + "' references undefined enricher '" + name + "'");
                }
            }
        }
        if (rule.filters() != null) {
            for (String name : rule.filters()) {
                if (!filters.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "logger '" + logger + "' references undefined filter '" + name + "'");
                }
            }
        }
    }

    private static void validateEncoders(
            Map<String, EncoderConfig> encoders,
            Map<String, JsonProfileConfig> profiles) {
        for (EncoderConfig encoder : encoders.values()) {
            if (encoder instanceof JsonEncoderConfig json
                    && !BUILT_IN_JSON_PROFILES.contains(json.profile())
                    && !profiles.containsKey(json.profile())) {
                throw new IllegalArgumentException("encoder '" + encoder.name()
                        + "' references unknown JSON profile '" + json.profile() + "'");
            }
        }
    }

    private static void validateOutput(
            OutputConfig output,
            Map<String, FormatterConfig> formatters,
            Map<String, EncoderConfig> encoders) {
        if (output instanceof ConsoleOutputConfig console) {
            requireReference("output", output.name(), "formatter", console.formatter(), formatters);
        } else if (output instanceof JsonStreamOutputConfig stream) {
            requireReference("output", output.name(), "encoder", stream.encoder(), encoders);
        } else if (output instanceof JsonFileOutputConfig file) {
            requireReference("output", output.name(), "encoder", file.encoder(), encoders);
        } else if (output instanceof CustomOutputConfig custom) {
            requireReference("custom output", output.name(), "formatter", custom.formatter(), formatters);
            requireReference("custom output", output.name(), "encoder", custom.encoder(), encoders);
        }
    }

    private static void requireReference(
            String kind,
            String owner,
            String referenceKind,
            String reference,
            Map<String, ?> values) {
        if (reference != null && !values.containsKey(reference)) {
            throw new IllegalArgumentException(kind + " '" + owner + "' references unknown "
                    + referenceKind + " '" + reference + "'");
        }
    }

    private static long aggregateDeliverySlots(
            DeliveryConfig inherited,
            Map<String, OutputConfig> outputs) {
        long slots = 0L;
        for (OutputConfig output : outputs.values()) {
            DeliveryConfig effective = inherited.withOverride(output.delivery());
            if (effective.asynchronous()
                    || output instanceof CustomOutputConfig) {
                slots = Math.addExact(slots, effective.capacity());
            }
        }
        return slots;
    }
}
