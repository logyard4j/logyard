package com.logyard4j.runtime.tools;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationRepeatedLoggerTest {
    @TempDir Path directory;

    @Test
    void repeatedLoggersCannotReportExactWhenTheyReplaceDestinationsOrAdditivity() throws Exception {
        for (String second : List.of("<logger name=\"audit\" level=\"WARN\"/>",
                "<logger name=\"audit\" additivity=\"false\"><appender-ref ref=\"OUT\"/></logger>")) {
            String xml = fixture(first() + second);
            var result = LogbackMigration.migrate(xml.getBytes(StandardCharsets.UTF_8));
            assertTrue(result.valid(), result.validationError());
            assertEquals(MigrationOutcome.UNSUPPORTED, result.outcome());
            assertTrue(result.notes().stream().anyMatch(note -> note.contains("repeated") && note.contains("'audit'")),
                    result.notes().toString());

            Path input = directory.resolve("logback.xml");
            Path output = directory.resolve("logyard.toml");
            Files.writeString(input, xml);
            Run stdout = run("migrate-logback", input.toString(), "--strict");
            assertRefused(stdout);
            Run file = run("migrate-logback", input.toString(), "--strict", "--output", output.toString());
            assertRefused(file);
            assertFalse(Files.exists(output));
            Files.writeString(output, "existing configuration");
            assertRefused(run("migrate-logback", input.toString(), "--strict", "--output", output.toString()));
            assertEquals("existing configuration", Files.readString(output));
            Files.delete(output);
        }
    }

    @Test
    void evenEmptyDeclarationsParticipateInDuplicateDetection() throws Exception {
        var result = LogbackMigration.migrate(fixture("<logger name=\"audit\"/><logger name=\"audit\"/>")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(MigrationOutcome.UNSUPPORTED, result.outcome());
    }

    @Test
    void caseDistinctLoggerNamesRemainSupported() throws Exception {
        var result = LogbackMigration.migrate(fixture(first() + first().replace("audit", "AUDIT"))
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(result.valid(), result.validationError());
        assertEquals(MigrationOutcome.EXACT, result.outcome(), result.notes().toString());
    }

    private static String first() {
        return "<logger name=\"audit\" level=\"INFO\" additivity=\"false\"><appender-ref ref=\"ERR\"/></logger>";
    }

    private static String fixture(String loggers) {
        return """
                <configuration>
                  <appender name="OUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>APPROVED %msg%n</pattern></encoder>
                  </appender>
                  <appender name="ERR" class="ch.qos.logback.core.ConsoleAppender">
                    <target>System.err</target><encoder><pattern>APPROVED %msg%n</pattern></encoder>
                  </appender>
                  <root level="INFO"><appender-ref ref="OUT"/></root>
                """ + loggers + "</configuration>";
    }

    private static void assertRefused(Run result) {
        assertEquals(3, result.status(), result.err());
        assertEquals("", result.out());
        assertTrue(result.err().contains("MIGRATION: UNSUPPORTED"), result.err());
        assertTrue(result.err().contains("repeated") && result.err().contains("'audit'"), result.err());
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = LogyardConfigTool.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Run(int status, String out, String err) { }
}
