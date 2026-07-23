package com.zsumz.logyard.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Decodes named formatter, encoder, JSON profile, enricher, and filter definitions. */
final class ExtensionSectionDecoder {
    private static final int MAX_COMPONENTS = 128;

    private ExtensionSectionDecoder() {
    }

    static Map<String, FormatterConfig> formatters(Map<String, Object> raw, String source, Map<String, String> environment) {
        requireComponentCount(raw, source, "formatters");
        Map<String, FormatterConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader reader = ConfigReader.fromValue(entry.getValue(), source, "formatters." + name, environment);
            String type = reader.requiredString("type").toLowerCase(Locale.ROOT);
            FormatterConfig formatter;
            try {
                formatter = switch (type) {
                    case "template" -> new TemplateFormatterConfig(name, reader.requiredString("template"));
                    case "custom" -> new ProviderFormatterConfig(name, ProviderReferenceDecoder.decode(reader));
                    default -> throw reader.failure("type", "must be template or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure(type.equals("template") ? "template" : "provider", exception.getMessage());
            }
            reader.finish();
            result.put(name, formatter);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, JsonProfileConfig> jsonProfiles(Map<String, Object> raw, String source, Map<String, String> environment) {
        requireComponentCount(raw, source, "json_profiles");
        Map<String, JsonProfileConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader reader = ConfigReader.fromValue(entry.getValue(), source, "json_profiles." + name, environment);
            String preset = reader.string("preset", "logyard");
            Map<String, String> rename = stringMap(reader.dynamicObject("rename"), reader, "rename", 32);
            List<String> drop = reader.stringList("drop", List.of());
            ConfigReader attributes = reader.object("attributes");
            String mode = attributes.string("mode", "nested");
            String prefix = attributes.string("prefix", "attributes.");
            List<String> include = attributes.stringList("include", List.of());
            List<String> exclude = attributes.stringList("exclude", List.of());
            Map<String, String> attributeRename = stringMap(attributes.dynamicObject("rename"), attributes, "rename", 128);
            attributes.finish();
            reader.finish();
            try {
                JsonAttributeTransformConfig transform =
                        new JsonAttributeTransformConfig(mode, prefix, include, exclude, attributeRename);
                result.put(name, new JsonProfileConfig(name, preset, rename, drop, transform));
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException(source + ": json_profiles." + name + ": " + exception.getMessage(), exception);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, EncoderConfig> encoders(Map<String, Object> raw, String source, Map<String, String> environment) {
        requireComponentCount(raw, source, "encoders");
        Map<String, EncoderConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader reader = ConfigReader.fromValue(entry.getValue(), source, "encoders." + name, environment);
            String type = reader.requiredString("type").toLowerCase(Locale.ROOT);
            EncoderConfig encoder;
            try {
                encoder = switch (type) {
                    case "json" -> new JsonEncoderConfig(name, reader.string("profile", "logyard"));
                    case "custom" -> new ProviderEncoderConfig(name, ProviderReferenceDecoder.decode(reader));
                    default -> throw reader.failure("type", "must be json or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure(type.equals("json") ? "profile" : "provider", exception.getMessage());
            }
            reader.finish();
            result.put(name, encoder);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, EnricherConfig> enrichers(Map<String, Object> raw, String source, Map<String, String> environment) {
        requireComponentCount(raw, source, "enrichers");
        Map<String, EnricherConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader reader = ConfigReader.fromValue(entry.getValue(), source, "enrichers." + name, environment);
            try {
                result.put(name, new EnricherConfig(name, ProviderReferenceDecoder.decode(reader)));
            } catch (IllegalArgumentException exception) {
                throw reader.failure("provider", exception.getMessage());
            }
            reader.finish();
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, FilterConfig> filters(Map<String, Object> raw, String source, Map<String, String> environment) {
        requireComponentCount(raw, source, "filters");
        Map<String, FilterConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            ConfigReader reader = ConfigReader.fromValue(entry.getValue(), source, "filters." + name, environment);
            String type = reader.requiredString("type").toLowerCase(Locale.ROOT);
            FilterConfig filter;
            try {
                filter = switch (type) {
                    case "sampling" -> new SamplingFilterConfig(
                            name,
                            reader.number("probability", 1.0d),
                            reader.string("key", "event-instance"),
                            reader.longInteger("seed", 0L));
                    case "rate_limit" -> new RateLimitFilterConfig(
                            name,
                            reader.number("permits_per_second", 100.0d),
                            reader.integer("burst", 100),
                            reader.string("key", "logger"),
                            reader.integer("max_keys", 1_024));
                    case "custom" -> new ProviderFilterConfig(name, ProviderReferenceDecoder.decode(reader));
                    default -> throw reader.failure("type", "must be sampling, rate_limit, or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure("type", exception.getMessage());
            }
            reader.finish();
            result.put(name, filter);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> stringMap(Map<String, Object> raw, ConfigReader reader, String key, int maximum) {
        if (raw.size() > maximum) {
            throw reader.failure(key, "must contain at most " + maximum + " entries");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                throw reader.failure(key + "." + entry.getKey(), "expected a string");
            }
            result.put(
                    entry.getKey(),
                    EnvironmentExpander.expand(
                            text, reader.environment(), reader.source(), reader.childPath(key + "." + entry.getKey())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requireComponentCount(Map<String, Object> raw, String source, String section) {
        if (raw.size() > MAX_COMPONENTS) {
            throw new ConfigurationException(source + ": " + section + ": at most " + MAX_COMPONENTS + " entries are supported");
        }
    }
}
