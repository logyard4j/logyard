package com.zsumz.logyard.config.loading.compiler;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.delivery.OverflowRuleConfig;
import com.zsumz.logyard.config.runtime.ContextConfig;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.config.runtime.RuntimeConfig;
import com.zsumz.logyard.config.runtime.ServiceConfig;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decodes singleton service, resource, runtime, context, and delivery sections. */
final class CoreSectionDecoder {
    private CoreSectionDecoder() {
    }

    static ServiceConfig service(ConfigReader reader) {
        String name = reader.string("name", "unknown-service");
        String namespace = reader.string("namespace", "");
        String version = reader.string("version", "unknown");
        String environment = reader.string("environment", "unknown");
        String instanceId = reader.string("instance_id", "unknown");
        reader.finish();
        try {
            return new ServiceConfig(name, namespace, version, environment, instanceId);
        } catch (IllegalArgumentException exception) {
            throw reader.failureFrom("name", exception);
        }
    }

    static ResourceConfig resource(ConfigReader reader) {
        List<String> include = reader.stringList("include", List.of());
        List<String> exclude = reader.stringList("exclude", List.of());
        Map<String, Object> raw = reader.dynamicObject("attributes");
        Map<String, String> attributes = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(value instanceof String text)) {
                throw reader.failure("attributes." + key, "expected a string");
            }
            attributes.put(
                    key,
                    EnvironmentExpander.expand(text, reader.context(), reader.childPath("attributes." + key)));
        });
        reader.finish();
        try {
            return new ResourceConfig(attributes, include, exclude);
        } catch (IllegalArgumentException exception) {
            throw reader.failureFrom("attributes", exception);
        }
    }

    static RuntimeConfig runtime(ConfigReader reader) {
        Duration shutdown = reader.duration("shutdown_timeout", Duration.ofSeconds(3));
        String status = reader.string("internal_status", "warn").toLowerCase(Locale.ROOT);
        if (!Set.of("off", "error", "warn", "info", "debug").contains(status)) {
            throw reader.failure("internal_status", "must be off, error, warn, info, or debug");
        }
        boolean watch = reader.bool("watch", false);
        Duration debounce = reader.duration("reload_debounce", Duration.ofMillis(250));
        if (debounce.compareTo(Duration.ofSeconds(30)) > 0) {
            throw reader.failure("reload_debounce", "must be between 0s and 30s");
        }
        reader.finish();
        return new RuntimeConfig(shutdown, status, watch, debounce);
    }

    static ContextConfig context(ConfigReader reader) {
        boolean trace = reader.bool("trace", true);
        List<String> mdc = reader.stringList("mdc", List.of());
        List<String> baggage = reader.stringList("baggage", List.of());
        List<String> redact = reader.stringList("redact", List.of());
        reader.finish();
        try {
            return new ContextConfig(trace, mdc, baggage, redact);
        } catch (IllegalArgumentException exception) {
            throw reader.sectionFailureFrom(exception);
        }
    }

    static DeliveryConfig delivery(ConfigReader reader) {
        String mode = reader.string("mode", "async");
        int capacity = reader.integer("capacity", DeliveryConfig.DEFAULT_CAPACITY);
        Map<String, Object> rawOverflow = reader.dynamicObject("overflow");
        EnumMap<Level, OverflowRuleConfig> rules = defaultOverflow();
        for (Map.Entry<String, Object> entry : rawOverflow.entrySet()) {
            Level level =
                    ConfigurationCompiler.parseLevel(entry.getKey(), reader.context(), reader.childPath("overflow." + entry.getKey()));
            rules.put(level, overflowRule(entry.getValue(), reader, "overflow." + entry.getKey()));
        }
        reader.finish();
        try {
            return new DeliveryConfig(mode, capacity, rules);
        } catch (IllegalArgumentException exception) {
            throw reader.sectionFailureFrom(exception);
        }
    }

    private static OverflowRuleConfig overflowRule(Object value, ConfigReader parent, String path) {
        if (value instanceof String actionText) {
            return new OverflowRuleConfig(overflowAction(actionText, parent, path), Duration.ZERO);
        }
        ConfigReader rule = ConfigReader.fromValue(value, parent.context(), parent.childPath(path));
        OverflowAction action = overflowAction(rule.string("action", "drop"), rule, "action");
        Duration timeout = rule.duration("timeout", Duration.ZERO);
        rule.finish();
        return new OverflowRuleConfig(action, timeout);
    }

    private static OverflowAction overflowAction(String text, ConfigReader reader, String key) {
        try {
            return OverflowAction.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw reader.failure(key, "must be drop, wait_drop, block, sync, or stderr");
        }
    }

    private static EnumMap<Level, OverflowRuleConfig> defaultOverflow() {
        EnumMap<Level, OverflowRuleConfig> rules = new EnumMap<>(Level.class);
        rules.put(Level.TRACE, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.DEBUG, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.INFO, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.WARN, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.ERROR, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        return rules;
    }
}
