package com.zsumz.logyard.config.loading.compiler;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.delivery.DeliveryOverrideConfig;
import com.zsumz.logyard.config.extension.ProviderReferenceConfig;
import com.zsumz.logyard.config.output.ColorConfig;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.config.output.ExceptionConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;
import com.zsumz.logyard.config.output.JsonStreamOutputConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.output.RotationConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decodes output transports and their per-output delivery and formatting options. */
final class OutputSectionDecoder {
    static final int MAXIMUM_OUTPUTS = 128;
    private static final Duration MINIMUM_ROTATION_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_ROTATION_INTERVAL = Duration.ofDays(365);

    private OutputSectionDecoder() {
    }

    static Map<String, OutputConfig> outputs(
            Map<String, Object> raw,
            Path baseDirectory,
            ConfigSource source) {
        if (raw.isEmpty()) {
            throw source.failure("outputs", "at least one named output is required");
        }
        if (raw.size() > MAXIMUM_OUTPUTS) {
            throw source.failure("outputs", "at most " + MAXIMUM_OUTPUTS + " outputs are supported");
        }

        Map<String, OutputConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (!name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                throw source.failure("outputs." + name, "invalid output name");
            }
            ConfigReader output = ConfigReader.fromValue(entry.getValue(), source, "outputs." + name);
            String type = output.requiredString("type").toLowerCase(Locale.ROOT);
            OutputConfig parsed = switch (type) {
                case "console" -> console(name, output);
                case "stream" -> stream(name, output);
                case "file" -> file(name, output, baseDirectory);
                case "custom" -> custom(name, output);
                default -> throw output.failure("type", "must be console, stream, file, or custom");
            };
            result.put(name, parsed);
        }
        return Collections.unmodifiableMap(result);
    }

    private static ConsoleOutputConfig console(String name, ConfigReader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        String stream = output.string("stream", "stderr").toLowerCase(Locale.ROOT);
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw output.failure("stream", "must be stdout or stderr");
        }
        String formatter = output.nullableString("formatter");
        ColorConfig color = color(output.object("color"));
        ExceptionConfig exception = exception(output.object("exception"));
        DeliveryOverrideConfig delivery = deliveryOverride(output.object("delivery"));
        output.finish();
        return new ConsoleOutputConfig(name, minimum, stream, color, exception, formatter, delivery);
    }

    private static JsonStreamOutputConfig stream(String name, ConfigReader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        String stream = output.string("stream", "stdout").toLowerCase(Locale.ROOT);
        if (!Set.of("stdout", "stderr").contains(stream)) {
            throw output.failure("stream", "must be stdout or stderr");
        }
        String encoder = output.nullableString("encoder");
        Duration flush = output.duration("flush", Duration.ZERO);
        DeliveryOverrideConfig delivery = deliveryOverride(output.object("delivery"));
        output.finish();
        return new JsonStreamOutputConfig(name, minimum, stream, flush, encoder, delivery);
    }

    private static JsonFileOutputConfig file(String name, ConfigReader output, Path baseDirectory) {
        Level minimum = output.level("min_level", Level.TRACE);
        Path path = baseDirectory.resolve(output.requiredString("path")).normalize();
        int buffer = output.sizeAsInt("buffer", 256 * 1_024);
        if (buffer < 1_024 || buffer > 16 * 1_024 * 1_024) {
            throw output.failure("buffer", "must be between 1KiB and 16MiB");
        }
        String encoder = output.nullableString("encoder");
        Duration flush = output.duration("flush", Duration.ofSeconds(1));
        boolean append = output.bool("append", true);
        boolean fsync = output.bool("fsync", false);
        RotationConfig rotation = rotation(output.object("rotate"));
        DeliveryOverrideConfig delivery = deliveryOverride(output.object("delivery"));
        output.finish();
        return new JsonFileOutputConfig(name, minimum, path, buffer, flush, append, fsync, rotation, encoder, delivery);
    }

    private static CustomOutputConfig custom(String name, ConfigReader output) {
        Level minimum = output.level("min_level", Level.TRACE);
        ProviderReferenceConfig providerReference = ProviderReferenceDecoder.decode(output);
        String formatter = output.nullableString("formatter");
        String encoder = output.nullableString("encoder");
        DeliveryOverrideConfig delivery = deliveryOverride(output.object("delivery"));
        output.finish();
        try {
            return new CustomOutputConfig(name, minimum, providerReference, formatter, encoder, delivery);
        } catch (IllegalArgumentException exception) {
            throw output.failureFrom("provider", exception);
        }
    }

    private static DeliveryOverrideConfig deliveryOverride(ConfigReader reader) {
        String mode = reader.nullableString("mode");
        Integer capacity = reader.nullableInteger("capacity");
        reader.finish();
        try {
            return new DeliveryOverrideConfig(mode, capacity);
        } catch (IllegalArgumentException exception) {
            throw reader.failureFrom("delivery", exception);
        }
    }

    private static ColorConfig color(ConfigReader reader) {
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

    private static ExceptionConfig exception(ConfigReader reader) {
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

    private static RotationConfig rotation(ConfigReader reader) {
        if (reader.empty()) {
            return null;
        }
        long size = reader.size("size", 1L << 30);
        int keep = reader.integer("keep", 10);
        String compress = reader.string("compression", "none").toLowerCase(Locale.ROOT);
        Duration interval = reader.duration("interval", null);
        if (size < 1_024 || size > (1L << 50)) {
            throw reader.failure("size", "must be between 1KiB and 1PiB");
        }
        if (keep < 1 || keep > 10_000) {
            throw reader.failure("keep", "must be between 1 and 10000");
        }
        if (!Set.of("none", "gzip").contains(compress)) {
            throw reader.failure("compression", "must be none or gzip");
        }
        if (interval != null
                && (interval.compareTo(MINIMUM_ROTATION_INTERVAL) < 0
                        || interval.compareTo(MAXIMUM_ROTATION_INTERVAL) > 0)) {
            throw reader.failure("interval", "must be between 1s and 365d");
        }
        reader.finish();
        return new RotationConfig(size, keep, compress, interval);
    }

}
