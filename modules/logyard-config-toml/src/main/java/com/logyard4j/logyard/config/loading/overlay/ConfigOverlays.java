package com.logyard4j.logyard.config.loading.overlay;

import com.logyard4j.logyard.config.ConfigurationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Launch-time configuration overlays captured from the process.
 *
 * <p>The active profile comes from the {@code logyard.profile} system property, then
 * the {@code LOGYARD_PROFILE} environment variable. Key-level overrides come from the
 * {@code LOGYARD_OVERRIDES} environment variable (entries separated by newlines or
 * unquoted semicolons), then from {@code logyard.override.*} system properties in
 * alphabetical order; a later entry for the same key wins, so a system property beats
 * the environment. Managed runtimes capture overlays once per installation and reuse
 * them for file reloads and source handoffs. A new installation captures new values.</p>
 */
public record ConfigOverlays(String profile, List<OverrideEntry> overrides) {
    /** System property naming the active profile. */
    public static final String PROFILE_PROPERTY = "logyard.profile";
    /** Environment variable naming the active profile. */
    public static final String PROFILE_VARIABLE = "LOGYARD_PROFILE";
    /** System property prefix for one key-level override each. */
    public static final String OVERRIDE_PROPERTY_PREFIX = "logyard.override.";
    /** Environment variable holding newline- or semicolon-separated overrides. */
    public static final String OVERRIDES_VARIABLE = "LOGYARD_OVERRIDES";
    /** Maximum number of override entries. */
    public static final int MAX_OVERRIDES = 64;

    private static final int MAX_BLOCK_CHARS = MAX_OVERRIDES
            * (OverrideEntry.MAX_KEY_CHARS + OverrideEntry.MAX_VALUE_CHARS + 4);

    private static final ConfigOverlays NONE = new ConfigOverlays(null, List.of());

    public ConfigOverlays {
        profile = trimToNull(profile);
        overrides = List.copyOf(Objects.requireNonNull(overrides, "overrides"));
        if (overrides.size() > MAX_OVERRIDES) {
            throw new ConfigurationException("at most " + MAX_OVERRIDES + " configuration overrides are supported");
        }
    }

    /** Returns overlays with no profile and no overrides. */
    public static ConfigOverlays none() {
        return NONE;
    }

    /** Captures overlays from the given environment and system properties. */
    public static ConfigOverlays fromProcess(Map<String, String> environment, Properties systemProperties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperties, "systemProperties");
        String profile = trimToNull(systemProperties.getProperty(PROFILE_PROPERTY));
        if (profile == null) {
            profile = trimToNull(environment.get(PROFILE_VARIABLE));
        }
        List<OverrideEntry> entries = new ArrayList<>();
        String block = environment.get(OVERRIDES_VARIABLE);
        if (block != null) {
            entries.addAll(variableEntries(block));
        }
        entries.addAll(propertyEntries(systemProperties));
        return new ConfigOverlays(profile, entries);
    }

    private static List<OverrideEntry> variableEntries(String block) {
        if (block.length() > MAX_BLOCK_CHARS) {
            throw new ConfigurationException(OVERRIDES_VARIABLE + ": override block exceeds " + MAX_BLOCK_CHARS + " characters");
        }
        List<OverrideEntry> entries = new ArrayList<>();
        int index = 0;
        for (String entry : splitUnquoted(block)) {
            String trimmed = entry.trim();
            String origin = OVERRIDES_VARIABLE + "[" + index + "]";
            if (trimmed.isEmpty()) {
                continue;
            }
            index++;
            int assignment = unquotedAssignment(trimmed);
            if (assignment <= 0) {
                throw new ConfigurationException(origin + ": override entry must be 'key.path = value'");
            }
            entries.add(new OverrideEntry(
                    trimmed.substring(0, assignment).trim(),
                    trimmed.substring(assignment + 1),
                    origin));
        }
        return entries;
    }

    private static List<OverrideEntry> propertyEntries(Properties systemProperties) {
        List<OverrideEntry> entries = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String name : systemProperties.stringPropertyNames()) {
            if (name.startsWith(OVERRIDE_PROPERTY_PREFIX)) {
                if (names.size() == MAX_OVERRIDES) {
                    throw new ConfigurationException("at most " + MAX_OVERRIDES + " configuration overrides are supported");
                }
                names.add(name);
            }
        }
        names.sort(String::compareTo);
        for (String name : names) {
            String key = name.substring(OVERRIDE_PROPERTY_PREFIX.length());
            entries.add(new OverrideEntry(key, systemProperties.getProperty(name), "-D" + name));
        }
        return entries;
    }

    private static List<String> splitUnquoted(String block) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        QuoteState state = new QuoteState();
        for (int index = 0; index < block.length(); index++) {
            char character = block.charAt(index);
            if (!state.inQuote() && (character == '\n' || character == ';')) {
                addEntry(entries, current.toString());
                current.setLength(0);
                continue;
            }
            state.observe(character);
            current.append(character);
        }
        addEntry(entries, current.toString());
        return entries;
    }

    private static void addEntry(List<String> entries, String entry) {
        if (entry.isBlank()) return;
        if (entries.size() == MAX_OVERRIDES) {
            throw new ConfigurationException("at most " + MAX_OVERRIDES + " configuration overrides are supported");
        }
        entries.add(entry);
    }

    private static int unquotedAssignment(String entry) {
        QuoteState state = new QuoteState();
        for (int index = 0; index < entry.length(); index++) {
            char character = entry.charAt(index);
            if (!state.inQuote() && character == '=') {
                return index;
            }
            state.observe(character);
        }
        return -1;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Tracks TOML basic- and literal-string quoting while scanning one line. */
    private static final class QuoteState {
        private boolean basic;
        private boolean literal;
        private boolean escaped;

        boolean inQuote() {
            return basic || literal;
        }

        void observe(char character) {
            if (escaped) {
                escaped = false;
                return;
            }
            if (basic) {
                if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    basic = false;
                }
                return;
            }
            if (literal) {
                if (character == '\'') {
                    literal = false;
                }
                return;
            }
            if (character == '"') {
                basic = true;
            } else if (character == '\'') {
                literal = true;
            }
        }
    }
}
