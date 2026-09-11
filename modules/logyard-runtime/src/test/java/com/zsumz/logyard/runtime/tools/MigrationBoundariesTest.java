package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.api.Level;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationBoundariesTest {
    @Test
    void propertyCyclesAndExpandingGraphsFailWithinTheBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationProperties.expand("${cycle}", Map.of("cycle", "${cycle:-fallback}"), 100));
        assertThrows(IllegalArgumentException.class,
                () -> MigrationProperties.expand("${a}", Map.of("a", "${b}", "b", "${a}"), 100));
        assertThrows(IllegalArgumentException.class,
                () -> MigrationProperties.expand("${a}", Map.of("a", "${b}".repeat(10), "b", "x".repeat(100)), 500));
        assertEquals("${HOME:-logs}/events", MigrationProperties.expand("${directory}/events",
                Map.of("directory", "${HOME:-logs}"), 100));
    }

    @Test
    void theExpansionBudgetIsSharedAcrossTheWholeMigration() {
        LogbackModel model = new LogbackModel();
        model.properties.put("large", "x".repeat(MigrationProperties.MAX_CHARACTERS / 2));
        model.substitute("${large}");
        model.substitute("${large}");
        assertThrows(IllegalArgumentException.class, () -> model.substitute("${large}"));
    }

    @Test
    void notesCannotInjectConfigurationOrGrowWithoutBound() {
        LogbackModel model = new LogbackModel();
        model.note("appender\n[outputs.injected]\npath = 'bad'\u001b[2J");
        for (int index = 0; index < 1_000; index++) model.note("note " + index + "x".repeat(2_000));
        assertEquals(129, model.notes.size());
        assertFalse(model.notes.getFirst().contains("\n"));
        assertFalse(model.notes.getFirst().contains("\u001b"));
        assertTrue(model.notes.stream().allMatch(note -> note.length() <= 1_024));
    }

    @Test
    void excessiveXmlNestingIsRejectedBeforeMigrationTraversal() {
        byte[] deeplyNested = ("<Configuration>" + "<a>".repeat(150) + "</a>".repeat(150)
                + "</Configuration>").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> Log4j2Migration.migrate(deeplyNested));
    }

    @Test
    void theDefaultThresholdIsErrorAndUnsupportedDecisionsAreNotApproximated() throws Exception {
        var defaults = filter("<ThresholdFilter/>");
        var config = LogyardConfigLoader.parse(defaults.toml(), "migrated.toml", Path.of("."), Map.of());
        assertEquals(Level.ERROR, config.outputs().get("out").minimumLevel());
        var inverted = filter("<ThresholdFilter level=\"WARN\" onMatch=\"DENY\" onMismatch=\"NEUTRAL\"/>");
        assertFalse(inverted.toml().contains("min_level"));
        assertTrue(inverted.notes().stream().anyMatch(note -> note.contains("not converted")));
        var composite = filter("<Filters><ThresholdFilter level=\"WARN\"/><ThresholdFilter level=\"INFO\"/></Filters>");
        assertFalse(composite.toml().contains("min_level"));
        assertTrue(composite.notes().stream().anyMatch(note -> note.contains("composite filters")));
    }

    @Test
    void bothMigrationsKeepOffRootRoutesEmptyAfterLoading() throws Exception {
        var logback = LogbackMigration.migrate(("<configuration><appender name=\"out\""
                + " class=\"ch.qos.logback.core.ConsoleAppender\"/><root level=\"OFF\">"
                + "<appender-ref ref=\"out\"/></root></configuration>").getBytes(StandardCharsets.UTF_8));
        var log4j = Log4j2Migration.migrate(("<Configuration><Appenders><Console name=\"out\"/>"
                + "</Appenders><Loggers><Root level=\"OFF\"><AppenderRef ref=\"out\"/>"
                + "</Root></Loggers></Configuration>").getBytes(StandardCharsets.UTF_8));
        for (String toml : List.of(logback.toml(), log4j.toml())) {
            var config = LogyardConfigLoader.parse(toml, "migrated.toml", Path.of("."), Map.of());
            assertEquals(List.of(), config.rootLogger().outputs());
        }
    }

    private static Log4j2Migration.Result filter(String filter) throws Exception {
        return Log4j2Migration.migrate(("<Configuration><Appenders><Console name=\"out\">" + filter
                + "</Console></Appenders><Loggers><Root level=\"INFO\"><AppenderRef ref=\"out\"/>"
                + "</Root></Loggers></Configuration>").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void zeroMonitorIntervalStaysDisabledAndOffLoggersStaySilent() throws Exception {
        String xml = """
                <Configuration monitorInterval="0">
                  <Appenders><Console name="out"/></Appenders>
                  <Loggers>
                    <Root level="INFO"><AppenderRef ref="out"/></Root>
                    <Logger name="silent" level="OFF"><AppenderRef ref="out"/></Logger>
                  </Loggers>
                </Configuration>
                """;
        var result = Log4j2Migration.migrate(xml.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.valid());
        var config = LogyardConfigLoader.parse(result.toml(), "migrated.toml", Path.of("."), Map.of());
        assertFalse(config.runtime().watch());
        assertEquals(List.of(), config.loggers().get("silent").outputs());
    }
}
