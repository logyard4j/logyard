package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigToolBoundariesTest {
    @Test
    void boundsExplainAndMigrationBeforeParsing() throws Exception {
        var input = Files.createTempFile("logyard-config-tool-limit", ".txt");
        try {
            Files.write(input, new byte[LogyardConfigLoader.MAX_CONFIG_BYTES + 1]);
            for (String command : new String[] {"explain", "migrate-logback", "migrate-log4j2"}) {
                var output = new ByteArrayOutputStream();
                try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                    assertEquals(2, LogyardConfigTool.run(new String[] {command, input.toString()}, stream, stream));
                    assertTrue(output.toString(StandardCharsets.UTF_8).contains("exceeds"));
                }
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void rejectsMalformedUtf8InExplain() throws Exception {
        var input = Files.createTempFile("logyard-config-tool-utf8", ".toml");
        try {
            Files.write(input, new byte[] {(byte) 0xc3, 0x28});
            var output = new ByteArrayOutputStream();
            try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                assertEquals(2, LogyardConfigTool.run(new String[] {"explain", input.toString()}, stream, stream));
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }
}
