package com.logyard4j.config.validation.aggregate;

import com.logyard4j.config.delivery.DeliveryConfig;
import com.logyard4j.config.encoding.EncoderConfig;
import com.logyard4j.config.encoding.JsonEncoderConfig;
import com.logyard4j.config.encoding.JsonProfileConfig;
import com.logyard4j.config.formatting.FormatterConfig;
import com.logyard4j.config.logging.LoggerRuleConfig;
import com.logyard4j.config.output.ConsoleOutputConfig;
import com.logyard4j.config.output.CustomOutputConfig;
import com.logyard4j.config.output.JsonFileOutputConfig;
import com.logyard4j.config.output.JsonStreamOutputConfig;
import com.logyard4j.config.output.OutputConfig;
import com.logyard4j.config.processing.EnricherConfig;
import com.logyard4j.config.processing.FilterConfig;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Validates cross-section configuration references, output compatibility, and bounded delivery capacity. */
public final class LogyardConfigValidator {
    private static final Set<String> BUILT_IN_JSON_PROFILES = Set.of("logyard", "ecs", "compact");

    private LogyardConfigValidator() {
    }

    public static void validate(
            LoggerRuleConfig rootLogger,
            Map<String, LoggerRuleConfig> loggers,
            DeliveryConfig delivery,
            Map<String, OutputConfig> outputs,
            Map<String, FormatterConfig> formatters,
            Map<String, EncoderConfig> encoders,
            Map<String, JsonProfileConfig> jsonProfiles,
            Map<String, EnricherConfig> enrichers,
            Map<String, FilterConfig> filters,
            long maximumDeliverySlots) {
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
                throw new IllegalArgumentException("custom output '" + output.name() + "' must use asynchronous delivery");
            }
        }
        long slots = aggregateDeliverySlots(delivery, outputs);
        if (slots > maximumDeliverySlots) {
            throw new IllegalArgumentException(
                    "aggregate delivery capacity exceeds " + maximumDeliverySlots + " event slots: " + slots);
        }
    }

    public static boolean isBuiltInJsonProfile(String name) {
        return BUILT_IN_JSON_PROFILES.contains(name);
    }

    public static long aggregateDeliverySlots(DeliveryConfig inherited, Map<String, OutputConfig> outputs) {
        long slots = 0L;
        for (OutputConfig output : outputs.values()) {
            DeliveryConfig effective = inherited.withOverride(output.delivery());
            if (effective.asynchronous() || output instanceof CustomOutputConfig) {
                slots = Math.addExact(slots, effective.capacity());
            }
        }
        return slots;
    }

    private static void validateProcessorNames(
            Map<String, EnricherConfig> enrichers,
            Map<String, FilterConfig> filters) {
        LinkedHashSet<String> duplicates = new LinkedHashSet<>(enrichers.keySet());
        duplicates.retainAll(filters.keySet());
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("enricher and filter names must be distinct: " + duplicates);
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
                    throw new IllegalArgumentException("logger '" + logger + "' references unknown output '" + name + "'");
                }
            }
        }
        if (rule.enrich() != null) {
            for (String name : rule.enrich()) {
                if (!enrichers.containsKey(name)) {
                    throw new IllegalArgumentException("logger '" + logger + "' references undefined enricher '" + name + "'");
                }
            }
        }
        if (rule.filters() != null) {
            for (String name : rule.filters()) {
                if (!filters.containsKey(name)) {
                    throw new IllegalArgumentException("logger '" + logger + "' references undefined filter '" + name + "'");
                }
            }
        }
    }

    private static void validateEncoders(
            Map<String, EncoderConfig> encoders,
            Map<String, JsonProfileConfig> profiles) {
        for (EncoderConfig encoder : encoders.values()) {
            if (encoder instanceof JsonEncoderConfig json
                    && !isBuiltInJsonProfile(json.profile())
                    && !profiles.containsKey(json.profile())) {
                throw new IllegalArgumentException(
                        "encoder '" + encoder.name() + "' references unknown JSON profile '" + json.profile() + "'");
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
}
