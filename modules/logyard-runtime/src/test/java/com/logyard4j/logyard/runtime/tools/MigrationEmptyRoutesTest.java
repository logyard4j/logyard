package com.logyard4j.logyard.runtime.tools;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationEmptyRoutesTest {
    @Test
    void rootsWithoutReferencesDoNotAcquireUnreferencedOutputs() throws Exception {
        check("<root level=\"INFO\"/>", "<Root level=\"INFO\"/>", List.of());
    }

    @Test
    void nonadditiveChildrenStaySilentWhileAdditiveChildrenStillInherit() throws Exception {
        check("<root level=\"INFO\"><appender-ref ref=\"out\"/></root>",
                "<Root level=\"INFO\"><AppenderRef ref=\"out\"/></Root>", List.of("out"));
    }

    @Test
    void logbackProfileLevelChangesKeepTheBaseRootOutputs() throws Exception {
        var result = LogbackMigration.migrate(("<configuration><appender name=\"out\""
                + " class=\"ch.qos.logback.core.ConsoleAppender\"/>"
                + "<root level=\"INFO\"><appender-ref ref=\"out\"/></root>"
                + "<logger name=\"service\" level=\"INFO\" additivity=\"false\"><appender-ref ref=\"out\"/></logger>"
                + "<springProfile name=\"production\"><root level=\"WARN\"/>"
                + "<logger name=\"service\" level=\"WARN\" additivity=\"false\"/></springProfile>"
                + "</configuration>").getBytes(StandardCharsets.UTF_8));
        assertTrue(result.valid(), result.validationError());
        assertEquals(List.of("out"), LogyardConfigLoader.parse(result.toml(), "migrated.toml",
                Path.of("."), Map.of()).rootLogger().outputs());
        // The generated profile overrides the threshold without replacing the base outputs.
        assertTrue(result.toml().contains("production"));
        var selected = LogyardConfigLoader.parseDetailed(result.toml(), "migrated.toml", Path.of("."),
                Map.of(), new ConfigOverlays("production", List.of())).config();
        assertEquals(List.of("out"), selected.rootLogger().outputs());
        assertEquals(Level.WARN, selected.rootLogger().level());
        assertEquals(List.of("out"), selected.loggers().get("service").outputs());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("profile changes to additivity")));
    }

    private static void check(String rootBack, String root4j, List<String> rootOutputs) throws Exception {
        var back = LogbackMigration.migrate(("<configuration><appender name=\"out\""
                + " class=\"ch.qos.logback.core.ConsoleAppender\"/>" + rootBack
                + "<logger name=\"silent\" level=\"INFO\" additivity=\"FALSE\"/>"
                + "<logger name=\"inherited\" level=\"INFO\"/></configuration>")
                .getBytes(StandardCharsets.UTF_8));
        var four = Log4j2Migration.migrate(("<Configuration><Appenders><Console name=\"out\"/>"
                + "</Appenders><Loggers>" + root4j
                + "<Logger name=\"silent\" level=\"INFO\" additivity=\"FALSE\"/>"
                + "<Logger name=\"inherited\" level=\"INFO\"/></Loggers></Configuration>")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(back.valid(), back.validationError());
        assertTrue(four.valid(), four.validationError());
        for (String toml : List.of(back.toml(), four.toml())) {
            var config = LogyardConfigLoader.parse(toml, "migrated.toml", Path.of("."), Map.of());
            assertEquals(rootOutputs, config.rootLogger().outputs());
            assertEquals(List.of(), config.loggers().get("silent").outputs());
            assertNull(config.loggers().get("inherited").outputs());
        }
    }
}
