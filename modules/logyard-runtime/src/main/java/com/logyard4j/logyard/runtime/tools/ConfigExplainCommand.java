package com.logyard4j.logyard.runtime.tools;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.result.ConfigLocations;
import com.logyard4j.logyard.config.loading.result.LoadedConfiguration;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlaySet;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;
import com.logyard4j.logyard.config.logging.LoggerRuleConfig;
import com.logyard4j.logyard.config.toml.TomlParser;

import java.io.IOException;
import java.io.PrintStream;
import com.logyard4j.logyard.config.loading.source.BoundedConfigurationFile;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Prints the effective configuration and the origin of every value. */
final class ConfigExplainCommand {
    private ConfigExplainCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out) throws IOException {
        Path path = Path.of(arguments.requiredPath("configuration file")).toAbsolutePath().normalize();
        String text = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(BoundedConfigurationFile.read(path))).toString();
        ConfigOverlays overlays = LogyardConfigTool.overlays(arguments.flag("profile"));
        LoadedConfiguration loaded = LogyardConfigLoader.parseDetailed(
                text, path.toString(), path.getParent(), System.getenv(), overlays);
        ConfigOverlaySet.Variant selected = selectedVariant(path, text, overlays);
        out.println("configuration: " + path);
        out.println("values: selected inputs; environment expressions are shown as written");
        out.println("profile: " + (loaded.activeProfile() == null ? "(none)" : loaded.activeProfile())
                + (loaded.availableProfiles().isEmpty()
                        ? ""
                        : " (declared: " + String.join(", ", loaded.availableProfiles()) + ")"));
        String key = arguments.flag("key");
        String logger = arguments.flag("logger");
        if (key != null) {
            explainKey(selected, key, out);
        }
        if (logger != null) {
            explainLogger(loaded.config(), selected.locations(), logger, out);
        }
        if (key == null && logger == null) {
            out.println();
            dump(selected.root(), "", selected.locations(), out);
        }
        return 0;
    }

    private static ConfigOverlaySet.Variant selectedVariant(Path absolute, String text, ConfigOverlays overlays) {
        ConfigOverlaySet set =
                ConfigOverlaySet.analyze(TomlParser.parse(absolute.toString(), text), absolute.toString(), overlays);
        return set.variants().stream()
                .filter(set::isSelected)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no selected configuration variant"));
    }

    private static void explainKey(ConfigOverlaySet.Variant selected, String key, PrintStream out) {
        List<String> segments = keyPath(key);
        Object value = valueAt(selected.root(), segments);
        out.println();
        if (value == null) {
            out.println(key + " is not set (built-in default applies)");
            return;
        }
        out.println(key + " = " + TomlRender.value(value));
        // Origins are recorded under dotted joins of raw segments, so a quoted key must be
        // canonicalized before lookup or it would resolve to its enclosing table's line.
        out.println("  origin: " + origin(selected.locations(), String.join(".", segments)));
    }

    private static void explainLogger(
            LogyardConfig config, ConfigLocations locations, String logger, PrintStream out) {
        out.println();
        out.println("logger: " + logger);
        out.println("  level: " + resolve(config, logger, LoggerRuleConfig::level, locations, "level", out));
        out.println("  outputs: " + resolve(config, logger, LoggerRuleConfig::outputs, locations, "outputs", out));
        out.println("  enrich: " + resolve(config, logger, LoggerRuleConfig::enrich, locations, "enrich", out));
        out.println("  filters: " + resolve(config, logger, LoggerRuleConfig::filters, locations, "filters", out));
    }

    private static String resolve(
            LogyardConfig config,
            String logger,
            java.util.function.Function<LoggerRuleConfig, Object> field,
            ConfigLocations locations,
            String key,
            PrintStream out) {
        String candidate = logger;
        while (!candidate.isEmpty()) {
            LoggerRuleConfig rule = config.loggers().get(candidate);
            if (rule != null && field.apply(rule) != null) {
                return TomlRender.value(field.apply(rule))
                        + "  (rule '" + candidate + "', " + origin(locations, "loggers." + candidate + "." + key) + ")";
            }
            int dot = candidate.lastIndexOf('.');
            candidate = dot < 0 ? "" : candidate.substring(0, dot);
        }
        return TomlRender.value(field.apply(config.rootLogger()))
                + "  (root, " + origin(locations, "loggers.root." + key) + ")";
    }

    private static void dump(Map<String, Object> table, String path, ConfigLocations locations, PrintStream out) {
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested && !nested.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                dump(typed, childPath, locations, out);
                continue;
            }
            String rendered = childPath + " = " + TomlRender.value(entry.getValue());
            out.println(pad(rendered) + "  # " + origin(locations, childPath));
        }
    }

    private static Object valueAt(Map<String, Object> root, List<String> segments) {
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> table)) {
                return null;
            }
            current = table.get(segment);
        }
        return current;
    }

    private static List<String> keyPath(String key) {
        try {
            return com.logyard4j.logyard.config.toml.TomlFragment.parseAssignment("--key", key + " = 0").path();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid --key path '" + key + "'");
        }
    }

    private static String origin(ConfigLocations locations, String path) {
        String origin = locations.describe(path);
        return origin == null ? "default" : origin;
    }

    private static String pad(String text) {
        return text.length() >= 56 ? text : text + " ".repeat(56 - text.length());
    }
}
