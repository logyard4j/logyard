package com.zsumz.logyard.runtime.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.output.json.file.lease.FileLease;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FileOutputInstallationFailureTest {
    @Test
    void failedGlobalInstallationPreservesAnActivatedUnusedFileAndReleasesItsLease() throws Exception {
        Path directory = Files.createTempDirectory("logyard-installation-publication-");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(output, "KEEP-ME\n", StandardCharsets.UTF_8);
        ConfigurationInstallationRequest request = request(directory, fileConfig(output));
        RuntimeInstallationManager manager =
                new RuntimeInstallationManager(new RejectingGlobal(), ignored -> true, Map::of);

        assertThrows(IllegalStateException.class, () -> manager.acquireApplication(request));

        assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    private static ConfigurationInstallationRequest request(Path baseDirectory, String text) {
        return new ConfigurationInstallationRequest(
                "failing global installation",
                null,
                new Object(),
                () -> ConfigurationSnapshot.capture(
                        "failing global installation",
                        baseDirectory,
                        null,
                        text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String fileConfig(Path output) {
        return """
                schema = 1
                [runtime]
                internal_status = "off"
                [delivery]
                mode = "async"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = false
                flush = "0s"
                """.formatted(output);
    }

    private static final class RejectingGlobal implements GlobalRuntimeAccess {
        @Override
        public LogyardRuntime current() {
            return null;
        }

        @Override
        public void install(LogyardRuntime runtime) {
            throw new IllegalStateException("global runtime publication rejected");
        }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            return false;
        }
    }
}
