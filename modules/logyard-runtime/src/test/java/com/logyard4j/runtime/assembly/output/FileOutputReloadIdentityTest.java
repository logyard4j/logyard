package com.logyard4j.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.runtime.assembly.RuntimeAssembly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves that the durability and time-based rotation settings of a file output take part in its
 * reuse signature, so changing either at the same path is refused by the restart-required policy
 * rather than silently applied to an already-open file.
 */
final class FileOutputReloadIdentityTest {
    @Test
    void anUnchangedFileOutputIsReusedAcrossAReload() throws Exception {
        Path path = temporaryOutput("logyard-reload-identity-unchanged-");
        RuntimeAssembly first = LogyardRuntimeFactory.assemble(config(path, true, "1h"), null);
        RuntimeAssembly second = null;
        try {
            second = LogyardRuntimeFactory.assemble(config(path, true, "1h"), first);
            assertNotNull(first.plan().outputs().get("json"));
            assertSame(first.plan().outputs().get("json"), second.plan().outputs().get("json"));
        } finally {
            if (second != null) {
                second.closeCandidateOutputs(first, new IllegalStateException("test cleanup"));
            }
            first.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    @Test
    void changingFsyncAtTheSamePathRequiresARestart() throws Exception {
        Path path = temporaryOutput("logyard-reload-identity-fsync-");
        assertRestartRequired(path, config(path, true, "1h"), config(path, false, "1h"));
    }

    @Test
    void changingTheRotationIntervalAtTheSamePathRequiresARestart() throws Exception {
        Path path = temporaryOutput("logyard-reload-identity-interval-");
        assertRestartRequired(path, config(path, true, "1h"), config(path, true, "30m"));
    }

    @Test
    void addingARotationIntervalAtTheSamePathRequiresARestart() throws Exception {
        Path path = temporaryOutput("logyard-reload-identity-added-interval-");
        assertRestartRequired(path, sizeOnlyConfig(path), config(path, false, "1h"));
    }

    private static void assertRestartRequired(Path path, LogyardConfig before, LogyardConfig after) {
        RuntimeAssembly current = LogyardRuntimeFactory.assemble(before, null);
        try {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LogyardRuntimeFactory.assemble(after, current));

            assertTrue(failure.getMessage().contains("at locked path"), failure.getMessage());
            assertTrue(failure.getMessage().contains(path.toAbsolutePath().normalize().toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("restart the process"), failure.getMessage());
        } finally {
            current.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    private static Path temporaryOutput(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).resolve("application.jsonl");
    }

    private static LogyardConfig config(Path path, boolean fsync, String interval) {
        return parse("""
                fsync = %s
                rotate = { size = "1MiB", keep = 3, interval = "%s" }
                """.formatted(fsync, interval), path);
    }

    private static LogyardConfig sizeOnlyConfig(Path path) {
        return parse("""
                fsync = false
                rotate = { size = "1MiB", keep = 3 }
                """, path);
    }

    private static LogyardConfig parse(String durability, Path path) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                flush = "10ms"
                %s
                """.formatted(path, durability);
        return LogyardConfigLoader.parse(text, "file-durability.toml", Path.of("."), Map.of());
    }
}
