package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.ConfigurationException;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.delivery.DeliveryConfig;
import com.zsumz.logyard.config.encoding.EncoderConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.processing.EnricherConfig;
import com.zsumz.logyard.config.processing.FilterConfig;
import com.zsumz.logyard.config.runtime.ContextConfig;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.config.runtime.RuntimeConfig;
import com.zsumz.logyard.config.runtime.ServiceConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;
import com.zsumz.logyard.config.toml.TomlDocument;
import com.zsumz.logyard.config.toml.TomlParseException;
import com.zsumz.logyard.config.toml.TomlParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
        return parse(
                decodeStrictUtf8(BoundedConfigurationFile.read(absolute), absolute),
                absolute.toString(),
                absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent(),
                System.getenv());
    }

    private static String decodeStrictUtf8(byte[] bytes, Path source) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Logyard configuration is not valid UTF-8: " + source, invalidUtf8);
        }
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
        Map<String, ThemeConfig> themes = ThemeSectionDecoder.themes(
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

}
