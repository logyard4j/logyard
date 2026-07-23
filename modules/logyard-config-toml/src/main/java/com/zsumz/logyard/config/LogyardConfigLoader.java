package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.toml.TomlDocument;
import com.zsumz.logyard.config.toml.TomlParseException;
import com.zsumz.logyard.config.toml.TomlParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
        ConfigReader root = new ConfigReader(document.root(), source, "", environment);
        int schema = root.integer("schema", -1);
        if (schema != 1) {
            throw root.failure("schema", "must be 1");
        }
        ServiceConfig service = CoreSectionDecoder.service(root.object("service"));
        ResourceConfig resource = CoreSectionDecoder.resource(root.object("resource"));
        RuntimeConfig runtime = CoreSectionDecoder.runtime(root.object("runtime"));
        ContextConfig context = CoreSectionDecoder.context(root.object("context"));
        DeliveryConfig delivery = CoreSectionDecoder.delivery(root.object("delivery"));
        Map<String, FormatterConfig> formatters = ExtensionSectionDecoder.formatters(
                root.dynamicObject("formatters"), source, environment);
        Map<String, JsonProfileConfig> jsonProfiles = ExtensionSectionDecoder.jsonProfiles(
                root.dynamicObject("json_profiles"), source, environment);
        Map<String, EncoderConfig> encoders = ExtensionSectionDecoder.encoders(
                root.dynamicObject("encoders"), source, environment);
        Map<String, EnricherConfig> enrichers = ExtensionSectionDecoder.enrichers(
                root.dynamicObject("enrichers"), source, environment);
        Map<String, FilterConfig> filters = ExtensionSectionDecoder.filters(
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
            ConfigReader output = ConfigReader.fromValue(
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

    private static ConsoleOutputConfig parseConsoleOutput(String name, ConfigReader output) {
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

    private static JsonStreamOutputConfig parseStreamOutput(String name, ConfigReader output) {
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
            ConfigReader output,
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

    private static CustomOutputConfig parseCustomOutput(String name, ConfigReader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        ProviderReferenceConfig providerReference = ProviderReferenceDecoder.decode(output);
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

    private static DeliveryOverrideConfig parseDeliveryOverride(ConfigReader reader) {
        String mode = reader.nullableString("mode");
        Integer capacity = reader.nullableInteger("capacity");
        reader.finish();
        try {
            return new DeliveryOverrideConfig(mode, capacity);
        } catch (IllegalArgumentException exception) {
            throw reader.failure("delivery", exception.getMessage());
        }
    }

    private static ColorConfig parseColor(ConfigReader reader) {
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

    private static ExceptionConfig parseException(ConfigReader reader) {
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

    private static RotationConfig parseRotation(ConfigReader reader) {
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
            ConfigReader theme = ConfigReader.fromValue(
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
                levels.put(level, parseTextStyle(ConfigReader.fromValue(
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

    private static TextStyleConfig parseTextStyle(ConfigReader reader) {
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
        ConfigReader reader = ConfigReader.fromValue(value, source, path, environment);
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

    static Level parseLevel(String value, String source, String path) {
        try {
            return Level.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    source + ": " + path + ": " + exception.getMessage(), exception);
        }
    }

    static String expandEnvironment(
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

}
