package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmptyRootRouteTest {
    @TempDir Path directory;

    @Test
    void explicitEmptyRootStaysSilentWhileAnExplicitChildCanLog() throws Exception {
        String toml = """
                schema = 1
                [outputs.file]
                type = "file"
                path = "events.jsonl"
                [loggers]
                root = { level = "trace", outputs = [] }
                "enabled" = { outputs = ["file"] }
                """;
        var config = LogyardConfigLoader.parse(toml, "empty-root.toml", directory.toRealPath(), Map.of());
        try (var runtime = LogyardRuntimeFactory.create(config)) {
            runtime.logger("silent").error("must remain silent");
            runtime.logger("enabled.child").info("explicit child output");
        }
        var lines = Files.readAllLines(directory.resolve("events.jsonl"));
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("explicit child output"));
    }
}
