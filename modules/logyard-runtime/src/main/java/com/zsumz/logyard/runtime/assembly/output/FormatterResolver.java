package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.api.spi.formatting.TextFormatterProvider;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.formatting.ProviderFormatterConfig;
import com.zsumz.logyard.config.formatting.TemplateFormatterConfig;
import com.zsumz.logyard.config.theme.TextStyleConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;
import com.zsumz.logyard.output.console.style.AnsiStyle;
import com.zsumz.logyard.output.console.style.BuiltInThemes;
import com.zsumz.logyard.output.console.style.ConsoleTheme;
import com.zsumz.logyard.output.console.rendering.TemplateTextFormatter;
import com.zsumz.logyard.runtime.extension.ExtensionGuardrails;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;
import com.zsumz.logyard.runtime.extension.ProviderResolver;

import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves configured text formatters and console themes. */
public final class FormatterResolver {
    private static final Set<String> BUILT_IN_THEME_NAMES = Set.of("ember", "nord", "mono");

    private FormatterResolver() {
    }

    /**
     * Resolves one named formatter.
     *
     * @param config complete Logyard configuration
     * @param name formatter name, or {@code null} when an output has no formatter
     * @param extensions discovered extension registry
     * @return guarded formatter, or {@code null}
     */
    public static TextFormatter resolve(LogyardConfig config, String name, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        if (name == null) {
            return null;
        }

        FormatterConfig configured = config.formatters().get(name);
        TextFormatter created;
        if (configured instanceof TemplateFormatterConfig template) {
            created = new TemplateTextFormatter(template.template(), ZoneId.systemDefault());
        } else if (configured instanceof ProviderFormatterConfig custom) {
            TextFormatterProvider provider = ProviderResolver.resolve(
                    extensions.formatters(),
                    custom.providerReference(),
                    "formatter '" + name + "'",
                    TextFormatterProvider::configurationSpec);
            created = Objects.requireNonNull(
                    provider.create(custom.providerReference().configuration()),
                    "formatter provider returned null: " + name);
        } else {
            throw new IllegalArgumentException("unknown formatter definition '" + name + "'");
        }
        return ExtensionGuardrails.formatter(created);
    }

    /**
     * Resolves a built-in or configured console theme.
     *
     * @param config complete Logyard configuration
     * @param name theme name
     * @return resolved console theme
     */
    public static ConsoleTheme consoleTheme(LogyardConfig config, String name) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(name, "name");
        ThemeConfig custom = config.themes().get(name);
        if (custom == null) {
            if (!BUILT_IN_THEME_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("console output references unknown theme '" + name + "'");
            }
            return BuiltInThemes.named(name);
        }

        Map<String, AnsiStyle> roles = new LinkedHashMap<>();
        custom.roles().forEach((role, value) -> roles.put(role, style(value)));
        EnumMap<Level, AnsiStyle> levels = new EnumMap<>(Level.class);
        custom.levels().forEach((level, value) -> levels.put(level, style(value)));
        return new ConsoleTheme(name, roles, levels);
    }

    /** Validates every provider-backed formatter definition without creating formatter instances. */
    public static void validateDefinitions(LogyardConfig config, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        for (FormatterConfig formatter : config.formatters().values()) {
            if (formatter instanceof ProviderFormatterConfig custom) {
                ProviderResolver.resolve(
                        extensions.formatters(),
                        custom.providerReference(),
                        "formatter '" + custom.name() + "'",
                        TextFormatterProvider::configurationSpec);
            }
        }
    }

    private static AnsiStyle style(TextStyleConfig value) {
        return new AnsiStyle(
                value.foreground(),
                value.background(),
                Boolean.TRUE.equals(value.bold()),
                Boolean.TRUE.equals(value.dim()),
                Boolean.TRUE.equals(value.italic()),
                Boolean.TRUE.equals(value.underline()));
    }
}
