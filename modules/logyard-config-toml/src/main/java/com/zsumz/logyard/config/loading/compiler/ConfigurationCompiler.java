package com.zsumz.logyard.config.loading.compiler;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.ConfigurationException;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.result.ConfigLocations;
import com.zsumz.logyard.config.loading.result.LoadedConfiguration;
import com.zsumz.logyard.config.loading.overlay.ConfigOverlaySet;
import com.zsumz.logyard.config.loading.overlay.ConfigOverlays;
import com.zsumz.logyard.config.loading.source.BoundedConfigurationFile;
import com.zsumz.logyard.config.loading.source.ConfigurationInputLimits;
import com.zsumz.logyard.config.output.OutputConfig;
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
public final class ConfigurationCompiler {
    public static final int MAX_CONFIG_BYTES = ConfigurationInputLimits.MAX_CONFIG_BYTES;
    public static final int MAX_EXPANDED_STRING_CHARS = ConfigurationInputLimits.MAX_EXPANDED_STRING_CHARS;

    private ConfigurationCompiler() {
    }

    public static LogyardConfig load(Path path) throws IOException {
        return loadDetailed(path, ConfigOverlays.none()).config();
    }

    /** Loads one file with explicit overlays, validating every declared profile. */
    public static LoadedConfiguration loadDetailed(Path path, ConfigOverlays overlays) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return parseDetailed(
                decodeStrictUtf8(BoundedConfigurationFile.read(absolute), absolute),
                absolute.toString(),
                absolute.getParent() == null ? Path.of(".").toAbsolutePath() : absolute.getParent(),
                System.getenv(),
                overlays);
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
        return parseDetailed(text, source, baseDirectory, environment, ConfigOverlays.none()).config();
    }

    /**
     * Parses with overlays: every declared profile is validated with the process
     * overrides applied, and the selected variant becomes the loaded configuration.
     */
    public static LoadedConfiguration parseDetailed(
            String text,
            String source,
            Path baseDirectory,
            Map<String, String> environment,
            ConfigOverlays overlays) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(baseDirectory, "baseDirectory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(overlays, "overlays");
        requireBoundedUtf8(text, source);
        TomlDocument document;
        try {
            document = TomlParser.parse(source, text);
        } catch (TomlParseException exception) {
            throw new ConfigurationException(exception.getMessage(), exception);
        }
        ConfigOverlaySet overlaid = ConfigOverlaySet.analyze(document, source, overlays);
        LogyardConfig selected = null;
        ConfigLocations selectedLocations = null;
        for (ConfigOverlaySet.Variant variant : overlaid.variants()) {
            LogyardConfig config;
            try {
                config = decode(variant.root(), new ConfigSource(source, environment, variant.locations()), baseDirectory);
            } catch (ConfigurationException failure) {
                throw variant.profile() == null
                        ? failure
                        : new ConfigurationException(
                                "profile '" + variant.profile() + "': " + failure.getMessage(), failure);
            }
            if (overlaid.isSelected(variant)) {
                selected = config;
                selectedLocations = variant.locations();
            }
        }
        return new LoadedConfiguration(
                Objects.requireNonNull(selected, "selected variant"),
                overlaid.activeProfile(),
                overlaid.profileNames(),
                selectedLocations);
    }

    private static LogyardConfig decode(Map<String, Object> values, ConfigSource sourceContext, Path baseDirectory) {
        ConfigReader root = new ConfigReader(values, sourceContext, "");
        int schema = root.integer("schema", -1);
        if (schema != 1) {
            throw root.failure("schema", "must be 1");
        }
        var service = CoreSectionDecoder.service(root.object("service"));
        var resource = CoreSectionDecoder.resource(root.object("resource"));
        var runtime = CoreSectionDecoder.runtime(root.object("runtime"));
        var context = CoreSectionDecoder.context(root.object("context"));
        var delivery = CoreSectionDecoder.delivery(root.object("delivery"));
        var formatters = ExtensionSectionDecoder.formatters(root.dynamicObject("formatters"), sourceContext);
        var jsonProfiles = ExtensionSectionDecoder.jsonProfiles(root.dynamicObject("json_profiles"), sourceContext);
        var encoders = ExtensionSectionDecoder.encoders(root.dynamicObject("encoders"), sourceContext);
        var enrichers = ExtensionSectionDecoder.enrichers(root.dynamicObject("enrichers"), sourceContext);
        var filters = ExtensionSectionDecoder.filters(root.dynamicObject("filters"), sourceContext);
        Map<String, OutputConfig> outputs =
                OutputSectionDecoder.outputs(root.dynamicObject("outputs"), baseDirectory, sourceContext);
        var themes = ThemeSectionDecoder.themes(root.dynamicObject("themes"), sourceContext);
        LoggerSectionDecoder.Bundle loggerBundle =
                LoggerSectionDecoder.decode(root.dynamicObject("loggers"), outputs, sourceContext);
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
        } catch (ConfigurationException located) {
            throw located;
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(sourceContext.name() + ": " + exception.getMessage(), exception);
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

    static Level parseLevel(String value, ConfigSource context, String path) {
        try {
            return Level.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    context.locate(path) + ": " + path + ": " + exception.getMessage(), exception);
        }
    }

}
