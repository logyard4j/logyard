package com.logyard4j.config.loading.overlay;

import com.logyard4j.api.Level;
import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.loading.result.LoadedConfiguration;
import com.logyard4j.config.loading.LogyardConfigLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies profile merging, override application, validation reach, and provenance. */
public final class ConfigOverlayBehaviorTest {
    private static final String BASE = """
            schema = 1
            [service]
            name = "orders"
            [delivery]
            capacity = 32
            [loggers]
            root = { level = "info", outputs = ["console"] }
            [outputs.console]
            type = "console"
            stream = "stderr"
            [profiles.prod.delivery]
            capacity = 64
            [profiles.prod.loggers]
            root = { level = "warn", outputs = ["console"] }
            [profiles.noisy.loggers]
            root = { level = "trace", outputs = ["console"] }
            """;

    @Test
    void baseConfigurationRunsWithoutAProfileAndListsAvailableOnes() {
        LoadedConfiguration loaded = load(BASE, ConfigOverlays.none());
        equal(32, loaded.config().delivery().capacity());
        equal(Level.INFO, loaded.config().rootLogger().level());
        equal(null, loaded.activeProfile());
        equal(List.of("prod", "noisy"), loaded.availableProfiles());
    }

    @Test
    void selectedProfileMergesOverTheBase() {
        LoadedConfiguration loaded = load(BASE, new ConfigOverlays("prod", List.of()));
        equal(64, loaded.config().delivery().capacity());
        equal(Level.WARN, loaded.config().rootLogger().level());
        equal("prod", loaded.activeProfile());
        equal("orders", loaded.config().service().name());
    }

    @Test
    void unknownProfileFailsListingTheAvailableOnes() {
        String message = failure(BASE, new ConfigOverlays("production", List.of()));
        check(message.contains("unknown profile 'production'"), message);
        check(message.contains("available: prod, noisy"), message);
    }

    @Test
    void everyProfileIsValidatedEvenWhenNotSelected() {
        String broken = BASE + """
                [profiles.staging.delivery]
                capactiy = 99
                """;
        String message = failure(broken, ConfigOverlays.none());
        check(message.startsWith("profile 'staging': "), message);
        check(message.contains("did you mean 'capacity'?"), message);
        check(message.contains("(profile 'staging')"), message);
    }

    @Test
    void profilesMayNotChangeTheSchemaVersion() {
        String broken = BASE + """
                [profiles.rogue]
                schema = 2
                """;
        String message = failure(broken, ConfigOverlays.none());
        check(message.contains("a profile may not set 'schema'"), message);
    }

    @Test
    void overridesBeatProfilesAndTheBase() {
        ConfigOverlays overlays = new ConfigOverlays(
                "prod", List.of(new OverrideEntry("delivery.capacity", "128", "-Dtest")));
        LoadedConfiguration loaded = load(BASE, overlays);
        equal(128, loaded.config().delivery().capacity());
        equal(Level.WARN, loaded.config().rootLogger().level());
    }

    @Test
    void overrideValueErrorsCiteTheOverrideOrigin() {
        ConfigOverlays overlays = new ConfigOverlays(
                null,
                List.of(new OverrideEntry("delivery.capacity", "plenty", "-Dlogyard.override.delivery.capacity")));
        String message = failure(BASE, overlays);
        check(message.startsWith("-Dlogyard.override.delivery.capacity: delivery.capacity:"), message);
        check(message.contains("expected an integer"), message);
    }

    @Test
    void overridesMayNotTouchReservedTables() {
        for (String key : List.of("schema", "profiles.extra.delivery.capacity")) {
            ConfigOverlays overlays = new ConfigOverlays(
                    null, List.of(new OverrideEntry(key, "9", "-Dtest")));
            String message = failure(BASE, overlays);
            check(message.contains("overrides may not set"), message);
        }
    }

    @Test
    void overridePathCollisionsAreExplicit() {
        ConfigOverlays overlays = new ConfigOverlays(
                null, List.of(new OverrideEntry("service.name.first", "x", "-Dtest")));
        String message = failure(BASE, overlays);
        check(message.contains("collides with an existing value at 'name'"), message);
    }

    @Test
    void quotedOverrideKeysAddressDottedLoggerNames() {
        ConfigOverlays overlays = new ConfigOverlays(
                null, List.of(new OverrideEntry("loggers.\"com.example\"", "debug", "-Dtest")));
        LoadedConfiguration loaded = load(BASE, overlays);
        equal(Level.DEBUG, loaded.config().loggers().get("com.example").level());
    }

    @Test
    void provenanceDescribesBaseProfileAndOverrideValues() {
        ConfigOverlays overlays = new ConfigOverlays(
                "prod", List.of(new OverrideEntry("service.name", "billing", "-Dlogyard.override.service.name")));
        LoadedConfiguration loaded = load(BASE, overlays);
        equal("billing", loaded.config().service().name());
        equal("-Dlogyard.override.service.name", loaded.locations().describe("service.name"));
        equal("layers.toml:12 (profile 'prod')", loaded.locations().describe("delivery.capacity"));
        equal("layers.toml:14 (profile 'prod')", loaded.locations().describe("loggers.root.level"));
        equal("layers.toml:10", loaded.locations().describe("outputs.console.stream"));
        equal(null, loaded.locations().describe("runtime.watch"));
    }

    private static LoadedConfiguration load(String text, ConfigOverlays overlays) {
        return LogyardConfigLoader.parseDetailed(text, "layers.toml", Path.of("."), Map.of(), overlays);
    }

    private static String failure(String text, ConfigOverlays overlays) {
        try {
            load(text, overlays);
            throw new AssertionError("configuration should fail");
        } catch (ConfigurationException expected) {
            return expected.getMessage();
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("unexpected diagnostic: " + message);
        }
    }
}
