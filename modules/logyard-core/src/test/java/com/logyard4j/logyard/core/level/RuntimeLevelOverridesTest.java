package com.logyard4j.logyard.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class RuntimeLevelOverridesTest {
    @Test
    void resolvesRootAndLoggerOverridesFromLeastToMostSpecific() {
        RuntimeLevelOverrides overrides = RuntimeLevelOverrides.empty()
                .withLevel("root", RuntimeLevelOverride.threshold(Level.INFO))
                .withLevel("com.acme", RuntimeLevelOverride.threshold(Level.DEBUG))
                .withLevel("com.acme.noisy", RuntimeLevelOverride.off());

        assertEquals(RuntimeLevelOverride.threshold(Level.INFO), overrides.resolve("org.example.Service"));
        assertEquals(RuntimeLevelOverride.threshold(Level.DEBUG), overrides.resolve("com.acme.Service"));
        assertEquals(RuntimeLevelOverride.off(), overrides.resolve("com.acme.noisy.Client"));
        assertEquals(
                Map.of(
                        "ROOT", RuntimeLevelOverride.threshold(Level.INFO),
                        "com.acme", RuntimeLevelOverride.threshold(Level.DEBUG),
                        "com.acme.noisy", RuntimeLevelOverride.off()),
                overrides.configuredLevels());
    }

    @Test
    void mutationReturnsNewSnapshotsAndNoopsPreserveIdentity() {
        RuntimeLevelOverrides empty = RuntimeLevelOverrides.empty();
        RuntimeLevelOverride debug = RuntimeLevelOverride.threshold(Level.DEBUG);
        RuntimeLevelOverrides configured = empty.withLevel("com.acme", debug);

        assertSame(configured, configured.withLevel("com.acme", debug));
        assertSame(configured, configured.withoutLevel("missing"));
        assertSame(empty, empty.clear());
        assertNull(empty.resolve("com.acme.Service"));
        assertNull(configured.withoutLevel("com.acme").resolve("com.acme.Service"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> configured.configuredLevels().put("other", RuntimeLevelOverride.off()));
    }

    @Test
    void validatesNamesAndAffectedHierarchies() {
        RuntimeLevelOverrides overrides = RuntimeLevelOverrides.empty();

        assertTrue(overrides.affects("ROOT", "anything"));
        assertTrue(overrides.affects("com.acme", "com.acme.Service"));
        assertFalse(overrides.affects("com.acme", "com.acmeish.Service"));
        assertThrows(IllegalArgumentException.class, () -> overrides.withLevel(" ", RuntimeLevelOverride.off()));
    }
}
