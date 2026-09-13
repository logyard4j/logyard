package com.logyard4j.logyard.runtime.tools;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.config.output.ConsoleOutputConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationDestinationTest {
    @Test
    void logbackPreservesCaseDistinctDestinationsAndFormattersThroughAliases() throws Exception {
        var result = LogbackMigration.migrate(bytes("""
                <configuration>
                  <appender name="Async" class="ch.qos.logback.classic.AsyncAppender">
                    <appender-ref ref="AUDIT"/>
                  </appender>
                  <appender name="AUDIT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>OUT %msg%n</pattern></encoder>
                  </appender>
                  <appender name="audit" class="ch.qos.logback.core.ConsoleAppender">
                    <target>System.err</target><encoder><pattern>ERR %msg%n</pattern></encoder>
                  </appender>
                  <appender name="audit-2" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>THIRD %msg%n</pattern></encoder>
                  </appender>
                  <root level="INFO"><appender-ref ref="Async"/><appender-ref ref="audit"/>
                    <appender-ref ref="audit-2"/></root>
                </configuration>
                """));
        assertTrue(result.valid(), result.validationError());
        assertDestinations(result.toml());
    }

    @Test
    void log4jPreservesCaseDistinctDestinationsAndFormattersThroughAliases() throws Exception {
        var result = Log4j2Migration.migrate(bytes("""
                <Configuration><Appenders>
                  <Async name="Async"><AppenderRef ref="AUDIT"/></Async>
                  <Console name="AUDIT"><PatternLayout pattern="OUT %msg%n"/></Console>
                  <Console name="audit" target="SYSTEM_ERR"><PatternLayout pattern="ERR %msg%n"/></Console>
                  <Console name="audit-2"><PatternLayout pattern="THIRD %msg%n"/></Console>
                </Appenders><Loggers><Root level="INFO">
                  <AppenderRef ref="Async"/><AppenderRef ref="audit"/><AppenderRef ref="audit-2"/>
                </Root></Loggers></Configuration>
                """));
        assertTrue(result.valid(), result.validationError());
        assertDestinations(result.toml());
    }

    @Test
    void logbackConsoleTargetDefaultsToStdoutAndInvalidValuesAreReported() throws Exception {
        for (String target : List.of("", "System.out", "System.err", "some-output")) {
            var result = LogbackMigration.migrate(bytes("""
                    <configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender">
                    %s<encoder><pattern>%%msg%%n</pattern></encoder></appender>
                    <root level="INFO"><appender-ref ref="C"/></root></configuration>
                    """.formatted(target.isEmpty() ? "" : "<target>" + target + "</target>")));
            assertEquals(target.equals("System.err") ? "stderr" : "stdout",
                    ((ConsoleOutputConfig) parse(result.toml()).outputs().get("c")).stream());
            assertEquals(target.equals("some-output"), result.notes().stream()
                    .anyMatch(note -> note.contains("unsupported console target")));
        }
    }

    @Test
    void referencesNeverFallBackToGeneratedNamesOrDifferentCase() {
        LogbackModel model = new LogbackModel();
        String first = model.outputName("AUDIT");
        model.outputs.put(first, Map.of("type", "console"));
        assertEquals(first, model.resolveOutput("AUDIT"));
        assertNull(model.resolveOutput("audit"));
        model.appenderAliases.put("a", "b");
        model.appenderAliases.put("b", "a");
        assertNull(model.resolveOutput("a"));
    }

    private static void assertDestinations(String toml) {
        LogyardConfig config = parse(toml);
        assertEquals(3, config.outputs().size());
        assertEquals(3, config.formatters().size());
        assertEquals(List.of("audit", "audit-2", "audit-2-2"), config.rootLogger().outputs());
        assertEquals("stdout", ((ConsoleOutputConfig) config.outputs().get("audit")).stream());
        assertEquals("stderr", ((ConsoleOutputConfig) config.outputs().get("audit-2")).stream());
        assertEquals("audit-format", ((ConsoleOutputConfig) config.outputs().get("audit")).formatter());
        assertEquals("audit-2-format", ((ConsoleOutputConfig) config.outputs().get("audit-2")).formatter());
    }

    private static LogyardConfig parse(String toml) {
        return LogyardConfigLoader.parse(toml, "migration.toml", Path.of("."), Map.of());
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }
}
