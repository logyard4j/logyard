package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class InstallationOverlayLifetimeTest {
    private static final String LEVEL = "logyard.override.loggers.root.level";
    private static final String PROFILE = "logyard.profile";
    private static final String CONFIG = """
            schema = 1
            [runtime]
            internal_status = "off"
            [outputs.console]
            type = "console"
            [loggers]
            root = { level = "info", outputs = [] }
            [profiles.prod.loggers]
            root = { level = "warn" }
            [profiles.dev.loggers]
            root = { level = "debug" }
            """;
    @TempDir Path directory;

    @Test
    void propertyChangesStayInactiveAcrossNoopCommentAndSemanticReloads() throws Exception {
        String original = System.getProperty(LEVEL);
        try {
            System.clearProperty(LEVEL);
            Path source = write(CONFIG);
            try (var bundle = LogyardBootstrap.start(source)) {
                assertEquals(Level.INFO, bundle.runtime().explain("example").level());
                System.setProperty(LEVEL, "debug");
                assertEquals(ReloadResult.UNCHANGED, bundle.reloadNow());
                assertEquals(Level.INFO, bundle.runtime().explain("example").level());
                Files.writeString(source, CONFIG + "\n# comment\n");
                assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
                assertEquals(Level.INFO, bundle.runtime().explain("example").level());
                System.setProperty(LEVEL, "invalid-late-override");
                Files.writeString(source, CONFIG.replace("level = \"info\"", "level = \"error\""));
                assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
                assertEquals(Level.ERROR, bundle.runtime().explain("example").level());
            }
        } finally {
            restore(LEVEL, original);
        }
    }

    @Test
    void onlyAFreshInstallationAdoptsChangedOverrides() throws Exception {
        String original = System.getProperty(LEVEL);
        try {
            Path source = write(CONFIG);
            System.setProperty(LEVEL, "warn");
            try (var bundle = LogyardBootstrap.start(source)) {
                System.setProperty(LEVEL, "debug");
                Files.writeString(source, CONFIG + "\n# reload\n");
                assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
                assertEquals(Level.WARN, bundle.runtime().explain("example").level());
            }
            try (var restarted = LogyardBootstrap.start(source)) {
                assertEquals(Level.DEBUG, restarted.runtime().explain("example").level());
            }
        } finally {
            restore(LEVEL, original);
        }
    }

    @Test
    void theSelectedProfileIsFixedWhileItsFileSettingsCanReload() throws Exception {
        String original = System.getProperty(PROFILE);
        try {
            Path source = write(CONFIG);
            System.setProperty(PROFILE, "prod");
            try (var bundle = LogyardBootstrap.start(source)) {
                assertEquals(Level.WARN, bundle.runtime().explain("example").level());
                System.setProperty(PROFILE, "dev");
                Files.writeString(source, CONFIG.replace("level = \"warn\"", "level = \"error\""));
                assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
                assertEquals(Level.ERROR, bundle.runtime().explain("example").level());
            }
        } finally {
            restore(PROFILE, original);
        }
    }

    @Test
    void frameworkSourceHandoffReusesTheInstallationInputs() throws Exception {
        String original = System.getProperty(LEVEL);
        try {
            System.clearProperty(LEVEL);
            try (var application = LogyardBootstrap.start(write(CONFIG))) {
                System.setProperty(LEVEL, "debug");
                var source = LogyardConfigurationSource.text("framework handoff",
                        CONFIG.replace("level = \"info\"", "level = \"warn\""), directory);
                try (var framework = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, source)) {
                    assertSame(application.runtime(), framework.runtime());
                    assertEquals(Level.WARN, framework.runtime().explain("example").level());
                }
            }
        } finally {
            restore(LEVEL, original);
        }
    }

    @Test
    void callerEnvironmentMutationsDoNotChangeCapturedInputs() throws Exception {
        Map<String, String> environment = new HashMap<>(Map.of("LOGYARD_PROFILE", "prod"));
        ConfigurationInputs inputs = ConfigurationInputs.capture(environment);
        environment.put("LOGYARD_PROFILE", "dev");
        assertEquals(Level.WARN, inputs.parse(ConfigurationSnapshot.read(write(CONFIG))).rootLogger().level());
    }

    private Path write(String content) throws Exception {
        Path source = directory.resolve("logyard.toml");
        Files.writeString(source, content);
        return source;
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}
