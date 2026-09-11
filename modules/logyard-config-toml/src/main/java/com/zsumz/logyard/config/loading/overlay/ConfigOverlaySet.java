package com.zsumz.logyard.config.loading.overlay;

import com.zsumz.logyard.config.ConfigurationException;
import com.zsumz.logyard.config.loading.result.ConfigLocations;
import com.zsumz.logyard.config.toml.TomlDocument;
import com.zsumz.logyard.config.toml.TomlFragment;
import com.zsumz.logyard.config.toml.TomlPositions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One parsed configuration document with its profiles split out and its overlays
 * resolved into decodable variants.
 *
 * <p>The base variant is the document without its {@code [profiles.*]} tables; each
 * profile variant is the base with that profile's tables deep-merged over it. Every
 * variant then receives the same key-level overrides, so validation covers exactly
 * what each launch configuration would run.</p>
 */
public final class ConfigOverlaySet {
    /** Maximum number of declared profiles. */
    public static final int MAX_PROFILES = 16;

    private static final Set<String> RESERVED_KEYS = Set.of("schema", "profiles");

    private final String sourceName;
    private final TomlDocument document;
    private final Map<String, Object> base;
    private final Map<String, Map<String, Object>> profiles;
    private final ConfigOverlays overlays;
    private final List<TomlFragment> fragments;

    private ConfigOverlaySet(
            String sourceName,
            TomlDocument document,
            Map<String, Object> base,
            Map<String, Map<String, Object>> profiles,
            ConfigOverlays overlays,
            List<TomlFragment> fragments) {
        this.sourceName = sourceName;
        this.document = document;
        this.base = base;
        this.profiles = profiles;
        this.overlays = overlays;
        this.fragments = fragments;
    }

    /** Splits profiles, parses overrides, and validates the requested selection. */
    public static ConfigOverlaySet analyze(TomlDocument document, String sourceName, ConfigOverlays overlays) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(overlays, "overlays");
        TomlPositions positions = document.positions();
        Map<String, Map<String, Object>> profiles = splitProfiles(document, sourceName, positions);
        Map<String, Object> base = new LinkedHashMap<>(document.root());
        base.remove("profiles");
        List<TomlFragment> fragments = new ArrayList<>(overlays.overrides().size());
        for (OverrideEntry entry : overlays.overrides()) {
            TomlFragment fragment = entry.parse();
            String first = fragment.path().getFirst();
            if (RESERVED_KEYS.contains(first)) {
                throw new ConfigurationException(entry.origin() + ": overrides may not set '" + first + "'");
            }
            fragments.add(fragment);
        }
        if (overlays.profile() != null && !profiles.containsKey(overlays.profile())) {
            String available = profiles.isEmpty()
                    ? "no profiles are defined"
                    : "available: " + String.join(", ", profiles.keySet());
            throw new ConfigurationException(
                    sourceName + ": profiles: unknown profile '" + overlays.profile() + "' (" + available + ")");
        }
        return new ConfigOverlaySet(sourceName, document, base, profiles, overlays, fragments);
    }

    /** Returns the declared profile names in file order. */
    public List<String> profileNames() {
        return List.copyOf(profiles.keySet());
    }

    /** Returns the selected profile name, or {@code null} for the base configuration. */
    public String activeProfile() {
        return overlays.profile();
    }

    /** Returns the base variant followed by every profile variant, overrides applied. */
    public List<Variant> variants() {
        List<Variant> variants = new ArrayList<>(profiles.size() + 1);
        variants.add(build(null));
        for (String name : profiles.keySet()) {
            variants.add(build(name));
        }
        return variants;
    }

    /** Returns whether this variant is the one the process selected. */
    public boolean isSelected(Variant variant) {
        return Objects.equals(variant.profile(), activeProfile());
    }

    private Variant build(String profileName) {
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, Object> root = OverlayMerge.deepCopy(base);
        if (profileName != null) {
            OverlayMerge.merge(
                    root, profiles.get(profileName), "", path -> profileOrigin(profileName, path), origins);
        }
        for (int index = 0; index < fragments.size(); index++) {
            OverlayMerge.applyOverride(root, fragments.get(index), overlays.overrides().get(index).origin(), origins);
        }
        return new Variant(profileName, root, ConfigLocations.of(sourceName, document.positions(), origins));
    }

    private String profileOrigin(String profile, String path) {
        int line = document.positions().lineOrAncestor("profiles." + profile + "." + path);
        return line >= 0
                ? sourceName + ":" + line + " (profile '" + profile + "')"
                : "profile '" + profile + "' in " + sourceName;
    }

    private static Map<String, Map<String, Object>> splitProfiles(
            TomlDocument document, String sourceName, TomlPositions positions) {
        Object declared = document.root().get("profiles");
        Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();
        if (declared == null) {
            return profiles;
        }
        if (!(declared instanceof Map<?, ?> table)) {
            throw fail(sourceName, positions, "profiles", "expected a table of profile tables");
        }
        if (table.size() > MAX_PROFILES) {
            throw fail(sourceName, positions, "profiles", "at most " + MAX_PROFILES + " profiles are supported");
        }
        for (Map.Entry<?, ?> entry : table.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw fail(sourceName, positions, "profiles." + name, "expected a profile table");
            }
            for (String reserved : RESERVED_KEYS) {
                if (body.containsKey(reserved)) {
                    throw fail(
                            sourceName, positions, "profiles." + name + "." + reserved,
                            "a profile may not set '" + reserved + "'");
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) body;
            profiles.put(name, typed);
        }
        return profiles;
    }

    private static ConfigurationException fail(
            String sourceName, TomlPositions positions, String path, String message) {
        int line = positions.lineOrAncestor(path);
        String located = line >= 0 ? sourceName + ":" + line : sourceName;
        return new ConfigurationException(located + ": " + path + ": " + message);
    }

    /** One decodable configuration variant with the origins of its overlaid values. */
    public record Variant(String profile, Map<String, Object> root, ConfigLocations locations) {
    }
}
