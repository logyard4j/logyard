package com.logyard4j.logyard.config.loading.overlay;

import com.logyard4j.logyard.config.ConfigurationException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Verifies launch-time overlay capture from environment variables and system properties. */
public final class OverlayCaptureTest {
    @Test
    void profilePropertyWinsOverEnvironmentVariable() {
        Properties properties = new Properties();
        properties.setProperty("logyard.profile", "prod");
        ConfigOverlays overlays = ConfigOverlays.fromProcess(Map.of("LOGYARD_PROFILE", "staging"), properties);
        equal("prod", overlays.profile());
        equal(null, ConfigOverlays.fromProcess(Map.of(), new Properties()).profile());
        equal("staging", ConfigOverlays.fromProcess(Map.of("LOGYARD_PROFILE", " staging "), new Properties()).profile());
    }

    @Test
    void environmentEntriesSplitOnUnquotedSeparatorsOnly() {
        String block = "delivery.capacity=64; service.name=\"a;b\"\nloggers.root.level=debug";
        ConfigOverlays overlays = ConfigOverlays.fromProcess(Map.of("LOGYARD_OVERRIDES", block), new Properties());
        List<OverrideEntry> entries = overlays.overrides();
        equal(3, entries.size());
        equal("delivery.capacity", entries.get(0).key());
        equal("64", entries.get(0).value());
        equal("LOGYARD_OVERRIDES[0]", entries.get(0).origin());
        equal("service.name", entries.get(1).key());
        equal("\"a;b\"", entries.get(1).value().trim());
        equal("loggers.root.level", entries.get(2).key());
    }

    @Test
    void malformedEnvironmentEntriesFailWithTheirIndex() {
        try {
            ConfigOverlays.fromProcess(Map.of("LOGYARD_OVERRIDES", "delivery.capacity"), new Properties());
            throw new AssertionError("entry without assignment should fail");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("LOGYARD_OVERRIDES[0]"), expected.getMessage());
        }
    }

    @Test
    void propertyOverridesAreSortedAndOriginNamed() {
        Properties properties = new Properties();
        properties.setProperty("logyard.override.delivery.capacity", "128");
        properties.setProperty("logyard.override.context.trace", "false");
        ConfigOverlays overlays = ConfigOverlays.fromProcess(Map.of(), properties);
        equal(2, overlays.overrides().size());
        equal("context.trace", overlays.overrides().get(0).key());
        equal("-Dlogyard.override.context.trace", overlays.overrides().get(0).origin());
        equal("delivery.capacity", overlays.overrides().get(1).key());
    }

    @Test
    void environmentEntriesComeBeforePropertyEntries() {
        Properties properties = new Properties();
        properties.setProperty("logyard.override.delivery.capacity", "128");
        ConfigOverlays overlays = ConfigOverlays.fromProcess(
                Map.of("LOGYARD_OVERRIDES", "delivery.capacity=64"), properties);
        equal("LOGYARD_OVERRIDES[0]", overlays.overrides().get(0).origin());
        equal("-Dlogyard.override.delivery.capacity", overlays.overrides().get(1).origin());
    }

    @Test
    void overrideCountAndSizesAreBounded() {
        StringBuilder block = new StringBuilder();
        for (int index = 0; index <= ConfigOverlays.MAX_OVERRIDES; index++) {
            block.append("delivery.capacity=").append(index).append('\n');
        }
        try {
            ConfigOverlays.fromProcess(Map.of("LOGYARD_OVERRIDES", block.toString()), new Properties());
            throw new AssertionError("override count should be bounded");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("at most"), expected.getMessage());
        }
        try {
            new OverrideEntry("delivery.capacity", "x".repeat(OverrideEntry.MAX_VALUE_CHARS + 1), "test");
            throw new AssertionError("override value should be bounded");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("exceeds"), expected.getMessage());
        }
    }

    @Test
    void bareStringsFallBackWhileStructuredValuesStayStrict() {
        equal("debug", new OverrideEntry("loggers.root.level", "debug", "test").parse().value());
        equal("2s", new OverrideEntry("outputs.app.flush", "2s", "test").parse().value());
        equal(64L, new OverrideEntry("delivery.capacity", "64", "test").parse().value());
        equal(Boolean.FALSE, new OverrideEntry("context.trace", "false", "test").parse().value());
        equal(List.of("a", "b"), new OverrideEntry("context.mdc", "[\"a\", \"b\"]", "test").parse().value());
        try {
            new OverrideEntry("context.mdc", "[\"a\", ", "test").parse();
            throw new AssertionError("malformed structured value should fail");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("invalid override"), expected.getMessage());
        }
    }

    @Test
    void quotedKeySegmentsAddressDottedNames() {
        var fragment = new OverrideEntry("loggers.\"com.example\".level", "debug", "test").parse();
        equal(List.of("loggers", "com.example", "level"), fragment.path());
        equal("debug", fragment.value());
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
