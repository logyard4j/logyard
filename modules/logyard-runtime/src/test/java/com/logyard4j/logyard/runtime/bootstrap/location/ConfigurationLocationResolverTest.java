package com.logyard4j.logyard.runtime.bootstrap.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConfigurationLocationResolverTest {
    @Test
    void resolvesClasspathFileUriAndRelativeFilesystemLocations() throws Exception {
        Path base = Files.createTempDirectory("logyard-framework-location-");
        Path absolute = base.resolve("absolute.toml");

        LogyardConfigurationSource classpath = ConfigurationLocationResolver.resolve(
                "Test Logyard",
                getClass().getClassLoader(),
                "classpath:/config/logyard.toml",
                base);
        LogyardConfigurationSource fileUri = ConfigurationLocationResolver.resolve(
                "Test Logyard",
                getClass().getClassLoader(),
                absolute.toUri().toString(),
                base);
        LogyardConfigurationSource relative = ConfigurationLocationResolver.resolve(
                "Test Logyard",
                getClass().getClassLoader(),
                "config/logyard.toml",
                base);

        assertEquals("classpath:config/logyard.toml", classpath.description());
        assertEquals(absolute.toAbsolutePath().normalize().toString(), fileUri.description());
        assertEquals(base.resolve("config/logyard.toml").toAbsolutePath().normalize().toString(), relative.description());
    }

    @Test
    void rejectsUnsupportedSchemesWithTheFrameworkDiagnosticPrefix() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ConfigurationLocationResolver.resolve(
                        "Quarkus Logyard",
                        getClass().getClassLoader(),
                        "https://example.invalid/logyard.toml",
                        Path.of(".")));

        assertEquals(
                "Quarkus Logyard configuration supports classpath: and filesystem locations, but was: https://example.invalid/logyard.toml",
                failure.getMessage());
    }

    @Test
    void normalizesClasspathLocationsBeforeFrameworkAliasComparison() {
        String canonical = "classpath:config/logyard.toml";
        assertEquals(canonical, ConfigurationLocationResolver.normalize(
                " \tclasspath:" + "/".repeat(50_000) + "config/logyard.toml\r\n"));
        assertEquals(canonical, ConfigurationLocationResolver.normalize("classpath:config/logyard.toml"));
    }

    @Test
    void rejectsOversizedNormalizedFrameworkLocations() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationLocationResolver.normalize(" \t" + "x".repeat(32_769) + "\r\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationLocationResolver.normalize("classpath:///" + "x".repeat(2_049)));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationLocationResolver.normalize("classpath:///"));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationLocationResolver.normalize("classpath:///nested\\logyard.toml"));
    }
}
