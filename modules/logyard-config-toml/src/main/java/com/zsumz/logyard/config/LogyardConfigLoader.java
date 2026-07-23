package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.delivery.OverflowAction;
import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.config.toml.TomlDocument;
import com.zsumz.logyard.config.toml.TomlParseException;
import com.zsumz.logyard.config.toml.TomlParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict compiler from TOML 1.0 values to Logyard's frozen schema. */
public final class LogyardConfigLoader {
    public static final int MAX_CONFIG_BYTES = 1_024 * 1_024;
    public static final int MAX_EXPANDED_STRING_CHARS = 65_536;

    private static final Set<String> THEME_ROLES = Set.of(
            "timestamp", "logger", "thread", "event", "message", "field_key",
            "field_value", "punctuation", "exception", "stack_frame");

    private LogyardConfigLoader() {
    }

    public static LogyardConfig load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        long size = Files.size(absolute);
        if (size > MAX_CONFIG_BYTES) {
            throw tooLarge(absolute.toString());
        }
        return parse(
                Files.readString(absolute),
                absolute.toString(),
                absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent(),
                System.getenv());
    }

    public static LogyardConfig parse(String text, String source, Path baseDirectory) {
        return parse(text, source, baseDirectory, System.getenv());
    }

    public static LogyardConfig parse(
            String text,
            String source,
            Path baseDirectory,
            Map<String, String> environment) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        Objects.requireNonNull(environment, "environment");
        requireBoundedUtf8(text, source);
        TomlDocument document;
        try {
            document = TomlParser.parse(source, text);
        } catch (TomlParseException exception) {
            throw new ConfigurationException(exception.getMessage(), exception);
        }
        Reader root = new Reader(document.root(), source, "", environment);
        int schema = root.integer("schema", -1);
        if (schema != 1) {
            throw root.failure("schema", "must be 1");
        }
        ServiceConfig service = parseService(root.object("service"));
        ResourceConfig resource = parseResource(root.object("resource"));
        RuntimeConfig runtime = parseRuntime(root.object("runtime"));
        ContextConfig context = parseContext(root.object("context"));
        DeliveryConfig delivery = parseDelivery(root.object("delivery"));
        Map<String, FormatterConfig> formatters = parseFormatters(
                root.dynamicObject("formatters"), source, environment);
        Map<String, JsonProfileConfig> jsonProfiles = parseJsonProfiles(
                root.dynamicObject("json_profiles"), source, environment);
        Map<String, EncoderConfig> encoders = parseEncoders(
                root.dynamicObject("encoders"), source, environment);
        Map<String, EnricherConfig> enrichers = parseEnrichers(
                root.dynamicObject("enrichers"), source, environment);
        Map<String, FilterConfig> filters = parseFilters(
                root.dynamicObject("filters"), source, environment);
        Map<String, OutputConfig> outputs = parseOutputs(
                root.dynamicObject("outputs"), baseDirectory, source, environment);
        Map<String, ThemeConfig> themes = parseThemes(
                root.dynamicObject("themes"), source, environment);
        LoggerBundle loggerBundle = parseLoggers(
                root.dynamicObject("loggers"), outputs, source, environment);
        root.finish();
        try {
            return new LogyardConfig(
                    schema,
                    service,
                    resource,
                    runtime,
                    context,
                    loggerBundle.root(),
                    loggerBundle.children(),
                    delivery,
                    outputs,
                    themes,
                    formatters,
                    encoders,
                    jsonProfiles,
                    enrichers,
                    filters);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(source + ": " + exception.getMessage(), exception);
        }
    }

    private static void requireBoundedUtf8(String text, String source) {
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character <= 0x7f) {
                bytes += 1;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_CONFIG_BYTES) {
                throw tooLarge(source);
            }
        }
    }

    private static ConfigurationException tooLarge(String source) {
        return new ConfigurationException(source + ": configuration exceeds "
                + MAX_CONFIG_BYTES + " UTF-8 bytes");
    }

    private static ServiceConfig parseService(Reader reader) {
        String name = reader.string("name", "unknown-service");
        String namespace = reader.string("namespace", "");
        String version = reader.string("version", "unknown");
        String environment = reader.string("environment", "unknown");
        String instanceId = reader.string("instance_id", "unknown");
        reader.finish();
        try {
            return new ServiceConfig(name, namespace, version, environment, instanceId);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("name", exception.getMessage());
        }
    }

    private static ResourceConfig parseResource(Reader reader) {
        Map<String, Object> raw = reader.dynamicObject("attributes");
        Map<String, String> attributes = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(value instanceof String text)) {
                throw reader.failure("attributes." + key, "expected a string");
            }
            attributes.put(key, expandEnvironment(
                    text, reader.environment, reader.source, reader.childPath("attributes." + key)));
        });
        reader.finish();
        try {
            return new ResourceConfig(attributes);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("attributes", exception.getMessage());
        }
    }

    private static RuntimeConfig parseRuntime(Reader reader) {
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

    private static ContextConfig parseContext(Reader reader) {
        boolean trace = reader.bool("trace", true);
        List<String> mdc = reader.stringList("mdc", List.of());
        List<String> baggage = reader.stringList("baggage", List.of());
        List<String> redact = reader.stringList("redact", List.of());
        reader.finish();
        try {
            return new ContextConfig(trace, mdc, baggage, redact);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("context", exception.getMessage());
        }
    }

    private static DeliveryConfig parseDelivery(Reader reader) {
        String mode = reader.string("mode", "async");
        int capacity = reader.integer("capacity", 65_536);
        Map<String, Object> rawOverflow = reader.dynamicObject("overflow");
        EnumMap<Level, OverflowRuleConfig> rules = defaultOverflow();
        for (Map.Entry<String, Object> entry : rawOverflow.entrySet()) {
            Level level = parseLevel(
                    entry.getKey(), reader.source, reader.childPath("overflow." + entry.getKey()));
            rules.put(level, parseOverflowRule(
                    entry.getValue(), reader, "overflow." + entry.getKey()));
        }
        reader.finish();
        try {
            return new DeliveryConfig(mode, capacity, rules);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("delivery", exception.getMessage());
        }
    }

    private static OverflowRuleConfig parseOverflowRule(
            Object value,
            Reader parent,
            String path) {
        if (value instanceof String actionText) {
            return new OverflowRuleConfig(parseOverflowAction(actionText, parent, path), Duration.ZERO);
        }
        Reader rule = Reader.fromValue(
                value, parent.source, parent.childPath(path), parent.environment);
        OverflowAction action = parseOverflowAction(
                rule.string("action", "drop"), rule, "action");
        Duration timeout = rule.duration("timeout", Duration.ZERO);
        rule.finish();
        return new OverflowRuleConfig(action, timeout);
    }

    private static OverflowAction parseOverflowAction(
            String text,
            Reader reader,
            String key) {
        try {
            return OverflowAction.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw reader.failure(key, "must be drop, block, sync, or stderr");
        }
    }

    private static EnumMap<Level, OverflowRuleConfig> defaultOverflow() {
        EnumMap<Level, OverflowRuleConfig> rules = new EnumMap<>(Level.class);
        rules.put(Level.TRACE, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.DEBUG, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.INFO, new OverflowRuleConfig(OverflowAction.DROP, Duration.ZERO));
        rules.put(Level.WARN, new OverflowRuleConfig(OverflowAction.STDERR, Duration.ofMillis(2)));
        rules.put(Level.ERROR, new OverflowRuleConfig(OverflowAction.STDERR, Duration.ZERO));
        return rules;
    }

    private static Map<String, FormatterConfig> parseFormatters(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        requireComponentCount(raw, source, "formatters");
        Map<String, FormatterConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Reader reader = Reader.fromValue(
                    entry.getValue(), source, "formatters." + name, environment);
            String type = reader.requiredString("type").toLowerCase(Locale.ROOT);
            FormatterConfig formatter;
            try {
                formatter = switch (type) {
                    case "template" -> new TemplateFormatterConfig(
                            name, reader.requiredString("template"));
                    case "custom" -> new ProviderFormatterConfig(
                            name, parseProviderReference(reader));
                    default -> throw reader.failure("type", "must be template or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure(type.equals("template") ? "template" : "provider",
                        exception.getMessage());
            }
            reader.finish();
            result.put(name, formatter);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, JsonProfileConfig> parseJsonProfiles(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        requireComponentCount(raw, source, "json_profiles");
        Map<String, JsonProfileConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Reader reader = Reader.fromValue(
                    entry.getValue(), source, "json_profiles." + name, environment);
            String preset = reader.string("preset", "logyard");
            Map<String, String> rename = parseStringMap(
                    reader.dynamicObject("rename"), reader, "rename", 32);
            List<String> drop = reader.stringList("drop", List.of());
            Reader attributes = reader.object("attributes");
            String mode = attributes.string("mode", "nested");
            String prefix = attributes.string("prefix", "attributes.");
            List<String> include = attributes.stringList("include", List.of());
            List<String> exclude = attributes.stringList("exclude", List.of());
            Map<String, String> attributeRename = parseStringMap(
                    attributes.dynamicObject("rename"), attributes, "rename", 128);
            attributes.finish();
            reader.finish();
            try {
                JsonAttributeTransformConfig transform = new JsonAttributeTransformConfig(
                        mode, prefix, include, exclude, attributeRename);
                result.put(name, new JsonProfileConfig(name, preset, rename, drop, transform));
            } catch (IllegalArgumentException exception) {
                throw new ConfigurationException(
                        source + ": json_profiles." + name + ": " + exception.getMessage(),
                        exception);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, EncoderConfig> parseEncoders(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        requireComponentCount(raw, source, "encoders");
        Map<String, EncoderConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Reader reader = Reader.fromValue(
                    entry.getValue(), source, "encoders." + name, environment);
            String type = reader.requiredString("type").toLowerCase(Locale.ROOT);
            EncoderConfig encoder;
            try {
                encoder = switch (type) {
                    case "json" -> new JsonEncoderConfig(name, reader.string("profile", "logyard"));
                    case "custom" -> new ProviderEncoderConfig(
                            name, parseProviderReference(reader));
                    default -> throw reader.failure("type", "must be json or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure(type.equals("json") ? "profile" : "provider",
                        exception.getMessage());
            }
            reader.finish();
            result.put(name, encoder);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, EnricherConfig> parseEnrichers(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        requireComponentCount(raw, source, "enrichers");
        Map<String, EnricherConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Reader reader = Reader.fromValue(
                    entry.getValue(), source, "enrichers." + name, environment);
            try {
                result.put(name, new EnricherConfig(name, parseProviderReference(reader)));
            } catch (IllegalArgumentException exception) {
                throw reader.failure("provider", exception.getMessage());
            }
            reader.finish();
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, FilterConfig> parseFilters(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        requireComponentCount(raw, source, "filters");
        Map<String, FilterConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            Reader reader = Reader.fromValue(
                    entry.getValue(), source, "filters." + name, environment);
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
                    case "custom" -> new ProviderFilterConfig(
                            name, parseProviderReference(reader));
                    default -> throw reader.failure(
                            "type", "must be sampling, rate_limit, or custom");
                };
            } catch (IllegalArgumentException exception) {
                throw reader.failure("type", exception.getMessage());
            }
            reader.finish();
            result.put(name, filter);
        }
        return Collections.unmodifiableMap(result);
    }

    private static ProviderReferenceConfig parseProviderReference(Reader reader) {
        String provider = reader.requiredString("provider");
        String implementation = reader.nullableString("implementation");
        ProviderConfiguration configuration = parseProviderConfiguration(
                reader.dynamicObject("config"), reader);
        try {
            return new ProviderReferenceConfig(provider, implementation, configuration);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("provider", exception.getMessage());
        }
    }

    private static ProviderConfiguration parseProviderConfiguration(
            Map<String, Object> raw,
            Reader reader) {
        LinkedHashMap<String, Object> flattened = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            flattenProviderValue(
                    entry.getKey(), entry.getValue(), flattened, reader, "config." + entry.getKey(), 1);
        }
        try {
            return flattened.isEmpty()
                    ? ProviderConfiguration.EMPTY
                    : new ProviderConfiguration(flattened);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("config", exception.getMessage());
        }
    }

    private static void flattenProviderValue(
            String key,
            Object value,
            Map<String, Object> flattened,
            Reader reader,
            String path,
            int depth) {
        if (depth > 4) {
            throw reader.failure(path, "provider configuration nesting exceeds four levels");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                throw reader.failure(path, "provider configuration table must not be empty");
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String child)) {
                    throw reader.failure(path, "provider configuration key is not a string");
                }
                flattenProviderValue(
                        key + "." + child,
                        entry.getValue(),
                        flattened,
                        reader,
                        path + "." + child,
                        depth + 1);
            }
            return;
        }
        Object normalized = normalizeProviderValue(value, reader, path);
        if (flattened.putIfAbsent(key, normalized) != null) {
            throw reader.failure(path, "duplicate flattened provider configuration key '" + key + "'");
        }
        if (flattened.size() > ProviderConfiguration.MAX_ENTRIES) {
            throw reader.failure("config", "provider configuration exceeds "
                    + ProviderConfiguration.MAX_ENTRIES + " entries");
        }
    }

    private static Object normalizeProviderValue(Object value, Reader reader, String path) {
        if (value instanceof String text) {
            return expandEnvironment(
                    text, reader.environment, reader.source, reader.childPath(path));
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    throw reader.failure(path + "[" + index + "]",
                            "provider configuration arrays must contain scalar values");
                }
                copy.add(normalizeProviderValue(item, reader, path + "[" + index + "]"));
            }
            return List.copyOf(copy);
        }
        throw reader.failure(path, "provider configuration values must be scalar or scalar arrays");
    }

    private static Map<String, String> parseStringMap(
            Map<String, Object> raw,
            Reader reader,
            String key,
            int maximum) {
        if (raw.size() > maximum) {
            throw reader.failure(key, "must contain at most " + maximum + " entries");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                throw reader.failure(key + "." + entry.getKey(), "expected a string");
            }
            result.put(entry.getKey(), expandEnvironment(
                    text,
                    reader.environment,
                    reader.source,
                    reader.childPath(key + "." + entry.getKey())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requireComponentCount(
            Map<String, Object> raw,
            String source,
            String section) {
        if (raw.size() > 128) {
            throw new ConfigurationException(
                    source + ": " + section + ": at most 128 entries are supported");
        }
    }

    private static Map<String, OutputConfig> parseOutputs(
            Map<String, Object> raw,
            Path baseDirectory,
            String source,
            Map<String, String> environment) {
        if (raw.isEmpty()) {
            throw new ConfigurationException(source + ": outputs: at least one named output is required");
        }
        if (raw.size() > 128) {
            throw new ConfigurationException(source + ": outputs: at most 128 outputs are supported");
        }
        Map<String, OutputConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (!name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw new ConfigurationException(
                        source + ": outputs." + name + ": invalid output name");
            }
            Reader output = Reader.fromValue(
                    entry.getValue(), source, "outputs." + name, environment);
            String type = output.requiredString("type").toLowerCase(Locale.ROOT);
            OutputConfig parsed = switch (type) {
                case "console" -> parseConsoleOutput(name, output);
                case "stream" -> parseStreamOutput(name, output);
                case "file" -> parseFileOutput(name, output, baseDirectory);
                case "custom" -> parseCustomOutput(name, output);
                default -> throw output.failure(
                        "type", "must be console, stream, file, or custom");
            };
            result.put(name, parsed);
        }
        return Collections.unmodifiableMap(result);
    }

    private static ConsoleOutputConfig parseConsoleOutput(String name, Reader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        String stream = output.string("stream", "stderr").toLowerCase(Locale.ROOT);
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw output.failure("stream", "must be stdout or stderr");
        }
        String formatter = output.nullableString("formatter");
        ColorConfig color = parseColor(output.object("color"));
        ExceptionConfig exception = parseException(output.object("exception"));
        DeliveryOverrideConfig delivery = parseDeliveryOverride(output.object("delivery"));
        output.finish();
        return new ConsoleOutputConfig(
                name, minimum, stream, color, exception, formatter, delivery);
    }

    private static JsonStreamOutputConfig parseStreamOutput(String name, Reader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        String stream = output.string("stream", "stdout").toLowerCase(Locale.ROOT);
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw output.failure("stream", "must be stdout or stderr");
        }
        String encoder = output.nullableString("encoder");
        Duration flush = output.duration("flush", Duration.ZERO);
        DeliveryOverrideConfig delivery = parseDeliveryOverride(output.object("delivery"));
        output.finish();
        return new JsonStreamOutputConfig(name, minimum, stream, flush, encoder, delivery);
    }

    private static JsonFileOutputConfig parseFileOutput(
            String name,
            Reader output,
            Path baseDirectory) {
        Level minimum = output.level("min_level", Level.TRACE);
        Path path = baseDirectory.resolve(output.requiredString("path")).normalize();
        int buffer = output.sizeAsInt("buffer", 256 * 1_024);
        if (buffer < 1_024 || buffer > 16 * 1_024 * 1_024) {
            throw output.failure("buffer", "must be between 1KiB and 16MiB");
        }
        String encoder = output.nullableString("encoder");
        Duration flush = output.duration("flush", Duration.ofSeconds(1));
        boolean append = output.bool("append", true);
        RotationConfig rotation = parseRotation(output.object("rotate"));
        DeliveryOverrideConfig delivery = parseDeliveryOverride(output.object("delivery"));
        output.finish();
        return new JsonFileOutputConfig(
                name, minimum, path, buffer, flush, append, rotation, encoder, delivery);
    }

    private static CustomOutputConfig parseCustomOutput(String name, Reader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        ProviderReferenceConfig providerReference = parseProviderReference(output);
        String formatter = output.nullableString("formatter");
        String encoder = output.nullableString("encoder");
        DeliveryOverrideConfig delivery = parseDeliveryOverride(output.object("delivery"));
        output.finish();
        try {
            return new CustomOutputConfig(
                    name, minimum, providerReference, formatter, encoder, delivery);
        } catch (IllegalArgumentException exception) {
            throw output.failure("provider", exception.getMessage());
        }
    }

    private static DeliveryOverrideConfig parseDeliveryOverride(Reader reader) {
        String mode = reader.nullableString("mode");
        Integer capacity = reader.nullableInteger("capacity");
        reader.finish();
        try {
            return new DeliveryOverrideConfig(mode, capacity);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("delivery", exception.getMessage());
        }
    }

    private static ColorConfig parseColor(Reader reader) {
        String mode = reader.string("mode", "auto").toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "always", "never").contains(mode)) {
            throw reader.failure("mode", "must be auto, always, or never");
        }
        String capability = reader.string("capability", "auto").toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "ansi16", "ansi256", "truecolor").contains(capability)) {
            throw reader.failure("capability", "must be auto, ansi16, ansi256, or truecolor");
        }
        String theme = reader.string("theme", "ember");
        reader.finish();
        return new ColorConfig(mode, capability, theme);
    }

    private static ExceptionConfig parseException(Reader reader) {
        String style = reader.string("style", "compact").toLowerCase(Locale.ROOT);
        if (!Set.of("compact", "full").contains(style)) {
            throw reader.failure("style", "must be compact or full");
        }
        String common = reader.string("common_frames", "collapse").toLowerCase(Locale.ROOT);
        if (!Set.of("collapse", "show").contains(common)) {
            throw reader.failure("common_frames", "must be collapse or show");
        }
        reader.finish();
        return new ExceptionConfig(style, common);
    }

    private static RotationConfig parseRotation(Reader reader) {
        if (reader.empty()) {
            return null;
        }
        long size = reader.size("size", 1L << 30);
        int keep = reader.integer("keep", 10);
        String compress = reader.string("compression", "none").toLowerCase(Locale.ROOT);
        if (size < 1_024 || size > (1L << 50)) {
            throw reader.failure("size", "must be between 1KiB and 1PiB");
        }
        if (keep < 1 || keep > 10_000) {
            throw reader.failure("keep", "must be between 1 and 10000");
        }
        if (!Set.of("none", "gzip").contains(compress)) {
            throw reader.failure("compression", "must be none or gzip");
        }
        reader.finish();
        return new RotationConfig(size, keep, compress);
    }

    private static Map<String, ThemeConfig> parseThemes(
            Map<String, Object> raw,
            String source,
            Map<String, String> environment) {
        Map<String, ThemeConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Reader theme = Reader.fromValue(
                    entry.getValue(), source, "themes." + entry.getKey(), environment);
            Map<String, TextStyleConfig> roles = new LinkedHashMap<>();
            for (String role : THEME_ROLES) {
                if (theme.has(role)) {
                    roles.put(role, parseTextStyle(theme.object(role)));
                }
            }
            EnumMap<Level, TextStyleConfig> levels = new EnumMap<>(Level.class);
            Map<String, Object> rawLevels = theme.dynamicObject("level");
            for (Map.Entry<String, Object> levelEntry : rawLevels.entrySet()) {
                Level level = parseLevel(
                        levelEntry.getKey(), source, "themes." + entry.getKey() + ".level");
                levels.put(level, parseTextStyle(Reader.fromValue(
                        levelEntry.getValue(),
                        source,
                        "themes." + entry.getKey() + ".level." + levelEntry.getKey(),
                        environment)));
            }
            theme.finish();
            result.put(entry.getKey(), new ThemeConfig(entry.getKey(), roles, levels));
        }
        return Collections.unmodifiableMap(result);
    }

    private static TextStyleConfig parseTextStyle(Reader reader) {
        String foreground = reader.nullableString("fg");
        String background = reader.nullableString("bg");
        Boolean bold = reader.nullableBoolean("bold");
        Boolean dim = reader.nullableBoolean("dim");
        Boolean italic = reader.nullableBoolean("italic");
        Boolean underline = reader.nullableBoolean("underline");
        reader.finish();
        return new TextStyleConfig(
                foreground, background, bold, dim, italic, underline);
    }

    private static LoggerBundle parseLoggers(
            Map<String, Object> raw,
            Map<String, OutputConfig> outputs,
            String source,
            Map<String, String> environment) {
        Object rootValue = raw.remove("root");
        LoggerRuleConfig root = rootValue == null
                ? new LoggerRuleConfig(
                        Level.INFO, List.copyOf(outputs.keySet()), List.of(), List.of())
                : parseLoggerRule(rootValue, true, outputs, source, "loggers.root", environment);
        if (root.level() == null) {
            root = new LoggerRuleConfig(
                    Level.INFO, root.outputs(), root.enrich(), root.filters());
        }
        if (root.outputs() == null || root.outputs().isEmpty()) {
            root = new LoggerRuleConfig(
                    root.level(), List.copyOf(outputs.keySet()), root.enrich(), root.filters());
        }
        if (root.enrich() == null) {
            root = new LoggerRuleConfig(
                    root.level(), root.outputs(), List.of(), root.filters());
        }
        if (root.filters() == null) {
            root = new LoggerRuleConfig(
                    root.level(), root.outputs(), root.enrich(), List.of());
        }
        Map<String, LoggerRuleConfig> children = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey().isBlank()) {
                throw new ConfigurationException(source + ": loggers: logger name must not be blank");
            }
            children.put(entry.getKey(), parseLoggerRule(
                    entry.getValue(), false, outputs, source,
                    "loggers." + entry.getKey(), environment));
        }
        return new LoggerBundle(root, Collections.unmodifiableMap(children));
    }

    private static LoggerRuleConfig parseLoggerRule(
            Object value,
            boolean root,
            Map<String, OutputConfig> outputs,
            String source,
            String path,
            Map<String, String> environment) {
        if (value instanceof String text) {
            return new LoggerRuleConfig(
                    parseLevel(text, source, path),
                    root ? List.copyOf(outputs.keySet()) : null,
                    root ? List.of() : null,
                    root ? List.of() : null);
        }
        Reader reader = Reader.fromValue(value, source, path, environment);
        Level level = root ? reader.level("level", Level.INFO) : reader.nullableLevel("level");
        List<String> selectedOutputs = root
                ? reader.stringList("outputs", List.copyOf(outputs.keySet()))
                : reader.nullableStringList("outputs");
        if (selectedOutputs != null) {
            for (String output : selectedOutputs) {
                if (!outputs.containsKey(output)) {
                    throw reader.failure("outputs", "unknown output '" + output + "'");
                }
            }
        }
        List<String> enrich = root
                ? reader.stringList("enrich", List.of())
                : reader.nullableStringList("enrich");
        List<String> filters = root
                ? reader.stringList("filters", List.of())
                : reader.nullableStringList("filters");
        reader.finish();
        return new LoggerRuleConfig(level, selectedOutputs, enrich, filters);
    }

    private static Level parseLevel(String value, String source, String path) {
        try {
            return Level.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    source + ": " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static String expandEnvironment(
            String value,
            Map<String, String> environment,
            String source,
            String path) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), MAX_EXPANDED_STRING_CHARS));
        int cursor = 0;
        while (cursor < value.length()) {
            if (value.startsWith("$${", cursor)) {
                appendBounded(result, "${", source, path);
                cursor += 3;
                continue;
            }
            int opening = value.indexOf("${", cursor);
            if (opening < 0) {
                appendBounded(result, value.substring(cursor), source, path);
                break;
            }
            appendBounded(result, value.substring(cursor, opening), source, path);
            int closing = value.indexOf('}', opening + 2);
            if (closing < 0) {
                throw new ConfigurationException(
                        source + ": " + path + ": unterminated environment expression");
            }
            String expression = value.substring(opening + 2, closing);
            int defaultAt = expression.indexOf(":-");
            String name = defaultAt < 0 ? expression : expression.substring(0, defaultAt);
            String fallback = defaultAt < 0 ? null : expression.substring(defaultAt + 2);
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new ConfigurationException(
                        source + ": " + path + ": invalid environment name '" + name + "'");
            }
            String replacement = environment.get(name);
            if (replacement == null) {
                if (fallback == null) {
                    throw new ConfigurationException(source + ": " + path
                            + ": environment variable " + name + " is not set and has no default");
                }
                replacement = fallback;
            }
            appendBounded(result, replacement, source, path);
            cursor = closing + 1;
        }
        return result.toString();
    }

    private static void appendBounded(
            StringBuilder result,
            String value,
            String source,
            String path) {
        if (result.length() + value.length() > MAX_EXPANDED_STRING_CHARS) {
            throw new ConfigurationException(source + ": " + path
                    + ": expanded string exceeds " + MAX_EXPANDED_STRING_CHARS + " characters");
        }
        result.append(value);
    }

    private record LoggerBundle(LoggerRuleConfig root, Map<String, LoggerRuleConfig> children) {
    }

    private static final class Reader {
        private final Map<String, Object> values;
        private final String source;
        private final String path;
        private final Map<String, String> environment;

        private Reader(
                Map<String, Object> values,
                String source,
                String path,
                Map<String, String> environment) {
            this.values = new LinkedHashMap<>(values);
            this.source = source;
            this.path = path;
            this.environment = environment;
        }

        static Reader fromValue(
                Object value,
                String source,
                String path,
                Map<String, String> environment) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigurationException(source + ": " + path + ": expected a table");
            }
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ConfigurationException(
                            source + ": " + path + ": table key is not a string");
                }
                converted.put(key, entry.getValue());
            }
            return new Reader(converted, source, path, environment);
        }

        boolean has(String key) {
            return values.containsKey(key);
        }

        boolean empty() {
            return values.isEmpty();
        }

        Reader object(String key) {
            Object value = values.remove(key);
            return value == null
                    ? new Reader(Map.of(), source, childPath(key), environment)
                    : fromValue(value, source, childPath(key), environment);
        }

        Map<String, Object> dynamicObject(String key) {
            Object value = values.remove(key);
            if (value == null) {
                return new LinkedHashMap<>();
            }
            return new LinkedHashMap<>(
                    fromValue(value, source, childPath(key), environment).values);
        }

        String requiredString(String key) {
            String value = nullableString(key);
            if (value == null) {
                throw failure(key, "is required");
            }
            return value;
        }

        String string(String key, String fallback) {
            String value = nullableString(key);
            return value == null ? fallback : value;
        }

        String nullableString(String key) {
            Object value = values.remove(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String string)) {
                throw failure(key, "expected a string");
            }
            return expandEnvironment(string, environment, source, childPath(key));
        }

        boolean bool(String key, boolean fallback) {
            Object value = values.remove(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Boolean flag)) {
                throw failure(key, "expected true or false");
            }
            return flag;
        }

        Boolean nullableBoolean(String key) {
            Object value = values.remove(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Boolean flag)) {
                throw failure(key, "expected true or false");
            }
            return flag;
        }

        int integer(String key, int fallback) {
            Integer value = nullableInteger(key);
            return value == null ? fallback : value;
        }

        Integer nullableInteger(String key) {
            Object value = values.remove(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Long number)) {
                throw failure(key, "expected an integer");
            }
            try {
                return Math.toIntExact(number);
            } catch (ArithmeticException exception) {
                throw failure(key, "integer is outside the supported range");
            }
        }

        long longInteger(String key, long fallback) {
            Object value = values.remove(key);
            if (value == null) {
                return fallback;
            }
            if (!(value instanceof Long number)) {
                throw failure(key, "expected an integer");
            }
            return number;
        }

        double number(String key, double fallback) {
            Object value = values.remove(key);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Long integer) {
                return integer.doubleValue();
            }
            if (value instanceof Double decimal) {
                return decimal;
            }
            throw failure(key, "expected a number");
        }

        Level level(String key, Level fallback) {
            String value = nullableString(key);
            return value == null ? fallback : parseLevel(value, source, childPath(key));
        }

        Level nullableLevel(String key) {
            String value = nullableString(key);
            return value == null ? null : parseLevel(value, source, childPath(key));
        }

        Duration duration(String key, Duration fallback) {
            String value = nullableString(key);
            if (value == null) {
                return fallback;
            }
            try {
                return DurationParser.parse(value);
            } catch (RuntimeException exception) {
                throw failure(key, exception.getMessage());
            }
        }

        long size(String key, long fallback) {
            String value = nullableString(key);
            if (value == null) {
                return fallback;
            }
            try {
                return SizeParser.parse(value);
            } catch (RuntimeException exception) {
                throw failure(key, exception.getMessage());
            }
        }

        int sizeAsInt(String key, int fallback) {
            long value = size(key, fallback);
            if (value > Integer.MAX_VALUE) {
                throw failure(key, "must be at most " + Integer.MAX_VALUE + " bytes");
            }
            return (int) value;
        }

        List<String> stringList(String key, List<String> fallback) {
            List<String> value = nullableStringList(key);
            return value == null ? fallback : value;
        }

        List<String> nullableStringList(String key) {
            Object value = values.remove(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof List<?> list)) {
                throw failure(key, "expected an array of strings");
            }
            if (list.size() > ContextConfig.MAX_ALLOWLIST_ENTRIES) {
                throw failure(key, "contains too many values");
            }
            List<String> result = new ArrayList<>(list.size());
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < list.size(); index++) {
                Object item = list.get(index);
                if (!(item instanceof String text)) {
                    throw failure(key + "[" + index + "]", "expected a string");
                }
                String expanded = expandEnvironment(
                        text, environment, source, childPath(key + "[" + index + "]"));
                if (!seen.add(expanded)) {
                    throw failure(key, "contains duplicate value '" + expanded + "'");
                }
                result.add(expanded);
            }
            return List.copyOf(result);
        }

        void finish() {
            if (values.isEmpty()) {
                return;
            }
            String unknown = values.keySet().iterator().next();
            String suggestion = nearest(unknown, knownKeys());
            String message = "unknown key '" + unknown + "'";
            if (suggestion != null) {
                message += "; did you mean '" + suggestion + "'?";
            }
            throw failure(unknown, message);
        }

        ConfigurationException failure(String key, String message) {
            return new ConfigurationException(source + ": " + childPath(key) + ": " + message);
        }

        String childPath(String child) {
            return path.isEmpty() ? child : path + "." + child;
        }

        private static Set<String> knownKeys() {
            return Set.of(
                    "schema", "service", "resource", "runtime", "context", "loggers",
                    "delivery", "outputs", "themes", "formatters", "encoders",
                    "json_profiles", "enrichers", "filters", "name", "namespace", "version",
                    "environment", "instance_id", "attributes", "shutdown_timeout",
                    "internal_status", "watch", "reload_debounce", "trace", "mdc",
                    "baggage", "redact", "mode", "capacity", "overflow", "action",
                    "timeout", "type", "stream", "min_level", "formatter", "encoder",
                    "provider", "implementation", "config", "template", "profile", "preset",
                    "rename", "drop", "prefix", "include", "exclude", "probability", "key",
                    "seed", "permits_per_second", "burst", "max_keys", "color", "exception",
                    "capability", "theme", "style", "common_frames", "path", "buffer",
                    "flush", "append", "rotate", "size", "keep", "compression",
                    "endpoint", "headers", "batch", "max_events", "max_bytes", "retry",
                    "max_attempts", "max_elapsed", "initial_backoff", "max_backoff",
                    "circuit_breaker", "failure_threshold", "open_duration", "level",
                    "enrich", "fg", "bg", "bold", "dim", "italic", "underline");
        }

        private static String nearest(String value, Set<String> candidates) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String candidate : candidates) {
                int distance = levenshtein(value, candidate);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return bestDistance <= Math.max(2, value.length() / 3) ? best : null;
        }

        private static int levenshtein(String left, String right) {
            int[] previous = new int[right.length() + 1];
            int[] current = new int[right.length() + 1];
            for (int index = 0; index <= right.length(); index++) {
                previous[index] = index;
            }
            for (int i = 1; i <= left.length(); i++) {
                current[0] = i;
                for (int j = 1; j <= right.length(); j++) {
                    int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                    current[j] = Math.min(
                            Math.min(current[j - 1] + 1, previous[j] + 1),
                            previous[j - 1] + cost);
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[right.length()];
        }
    }
}
