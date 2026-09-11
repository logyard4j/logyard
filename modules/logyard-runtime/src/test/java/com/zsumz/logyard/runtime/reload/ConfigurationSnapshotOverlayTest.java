package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.LogyardConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies that snapshot parsing applies the process profile and override overlays. */
public final class ConfigurationSnapshotOverlayTest {
    private static final String CONFIG = """
            schema = 1
            [delivery]
            capacity = 32
            [loggers]
            root = { level = "info", outputs = ["console"] }
            [outputs.console]
            type = "console"
            [profiles.prod.loggers]
            root = { level = "warn", outputs = ["console"] }
            """;

    @Test
    void snapshotParseAppliesProfileAndOverridesFromTheProcess() throws Exception {
        Path source = Files.createTempFile("logyard-overlay-", ".toml");
        String property = "logyard.override.delivery.capacity";
        try {
            Files.writeString(source, CONFIG);
            System.setProperty(property, "96");
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(source);
            LogyardConfig selected = snapshot.parse(Map.of("LOGYARD_PROFILE", "prod"));
            if (selected.rootLogger().level() != Level.WARN) {
                throw new AssertionError("profile from the environment should select the warn root level");
            }
            if (selected.delivery().capacity() != 96) {
                throw new AssertionError("system property override should replace the capacity");
            }
            LogyardConfig base = snapshot.parse(Map.of());
            if (base.rootLogger().level() != Level.INFO) {
                throw new AssertionError("without a profile the base root level should apply");
            }
            if (base.delivery().capacity() != 96) {
                throw new AssertionError("overrides should apply independently of profile selection");
            }
        } finally {
            System.clearProperty(property);
            Files.deleteIfExists(source);
        }
    }
}
