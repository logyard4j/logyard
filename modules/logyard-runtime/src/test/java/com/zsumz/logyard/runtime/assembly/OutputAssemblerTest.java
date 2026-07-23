package com.zsumz.logyard.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.LogyardConfigLoader;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OutputAssemblerTest {
    @Test
    void reusesOutputsOnlyWhenTheirCompleteSignatureMatches() {
        LogyardConfig config = consoleConfig();
        RuntimeAssembly first = LogyardRuntimeFactory.assemble(config, null);
        RuntimeAssembly second = LogyardRuntimeFactory.assemble(config, first);

        try {
            assertSame(first.plan().outputs().get("console"), second.plan().outputs().get("console"));
        } finally {
            second.closeCandidateOutputs(first, new IllegalStateException("test cleanup"));
            first.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    @Test
    void rejectsStructuralChangesToAnOpenFileOutput(@TempDir Path temporaryDirectory) {
        Path path = temporaryDirectory.resolve("application.jsonl");
        RuntimeAssembly current = LogyardRuntimeFactory.assemble(fileConfig(path, true), null);
        try {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LogyardRuntimeFactory.assemble(fileConfig(path, false), current));

            assertTrue(failure.getMessage().contains("at locked path"));
            assertTrue(failure.getMessage().contains("restart the process"));
        } finally {
            current.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    private static LogyardConfig consoleConfig() {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        return LogyardConfigLoader.parse(text, "console-output.toml", Path.of("."), Map.of());
    }

    private static LogyardConfig fileConfig(Path path, boolean append) {
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
                append = %s
                flush = "10ms"
                """.formatted(path, append);
        return LogyardConfigLoader.parse(text, "file-output.toml", Path.of("."), Map.of());
    }
}
