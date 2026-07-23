package com.zsumz.logyard.runtime.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ReloadCoordinatorTest {
    @Test
    void unchangedDigestIsNoop() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            assertEquals(ReloadResult.UNCHANGED, bundle.reloadNow());
        }
    }

    @Test
    void publishesLevelChangeToExistingLogger() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            LogyardLogger logger = bundle.runtime().logger("test.Logger");
            assertTrue(logger.isInfoEnabled());
            fixture.write("error", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            assertFalse(logger.isInfoEnabled());
            assertTrue(logger.isErrorEnabled());
        }
    }

    @Test
    void invalidTomlRollsBack() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("error", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            Files.writeString(fixture.source(), fixture.config("debug", "4KiB")
                    + "\ninvalid_key = true\n", StandardCharsets.UTF_8);
            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertEquals(Level.ERROR, bundle.config().rootLogger().level());
        }
    }

    @Test
    void identicalOutputIsReusedAcrossRouteReload() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("warn", "4KiB");
            assertEquals(ReloadResult.APPLIED, bundle.reloadNow());
            assertEquals(Level.WARN, bundle.config().rootLogger().level());
        }
    }

    @Test
    void lockedFileMutationIsRejected() throws Exception {
        Fixture fixture = Fixture.create();
        try (RuntimeBundle bundle = LogyardBootstrap.start(fixture.source())) {
            fixture.write("error", "8KiB");
            assertEquals(ReloadResult.REJECTED, bundle.reloadNow());
            assertEquals(Level.INFO, bundle.config().rootLogger().level());
        }
    }

    private record Fixture(Path directory, Path source, Path output) {
        static Fixture create() throws Exception {
            Path directory = Files.createTempDirectory("logyard-reload-test-");
            Fixture fixture = new Fixture(
                    directory,
                    directory.resolve("logyard.toml"),
                    directory.resolve("events.jsonl"));
            fixture.write("info", "4KiB");
            return fixture;
        }

        void write(String level, String buffer) throws Exception {
            Files.writeString(source, config(level, buffer), StandardCharsets.UTF_8);
        }

        String config(String level, String buffer) {
            return """
                    schema = 1
                    [service]
                    name = "test"
                    environment = "test"
                    version = "1"
                    [runtime]
                    shutdown_timeout = "2s"
                    internal_status = "off"
                    [delivery]
                    mode = "sync"
                    capacity = 16
                    [context]
                    [loggers]
                    root = { level = "%s", outputs = ["json"] }
                    [outputs.json]
                    type = "file"
                    path = "%s"
                    append = true
                    buffer = "%s"
                    flush = "0s"
                    """.formatted(level, output.toString().replace("\\", "\\\\"), buffer);
        }
    }
}
