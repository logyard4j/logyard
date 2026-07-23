package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.toml.TomlDocument;
import com.zsumz.logyard.config.toml.TomlParseException;
import com.zsumz.logyard.config.toml.TomlParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Strict compiler from TOML 1.0 values to Logyard's frozen schema. */
public final class LogyardConfigLoader {
    public static final int MAX_CONFIG_BYTES = 1_024 * 1_024;
    public static final int MAX_EXPANDED_STRING_CHARS = 65_536;

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
        Map<String, OutputConfig> outputs = OutputSectionDecoder.outputs(
                root.dynamicObject("outputs"), baseDirectory, source, environment);
        Map<String, ThemeConfig> themes = OutputSectionDecoder.themes(
                root.dynamicObject("themes"), source, environment);
        LoggerSectionDecoder.Bundle loggerBundle = LoggerSectionDecoder.decode(
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

}
