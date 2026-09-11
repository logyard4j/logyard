package com.logyard4j.runtime.tools;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.config.formatting.TemplateFormatterConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.output.console.rendering.TemplateTextFormatter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MigrationFidelityTest {
    @TempDir Path directory;

    @Test
    void messageFreePatternsPreserveExcludedContentInStrictMode() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            Run result = migrate(provider, "%level approved-marker%n", "--strict");
            assertEquals(0, result.status(), result.err());
            assertTrue(result.err().contains("MIGRATION: EXACT"), result.err());
            var config = LogyardConfigLoader.parse(result.out(), "migrated.toml", directory, Map.of());
            var template = (TemplateFormatterConfig) config.formatters().values().iterator().next();
            LogEvent event = new LogEvent(0, 0, Level.INFO, "private.logger", null,
                    "SENTINEL-SECRET", new Object[0], AttributeSet.builder().put("private", "secret").build(),
                    null, 1, "main");
            assertEquals("INFO approved-marker", new TemplateTextFormatter(template.template(), ZoneOffset.UTC).format(event));
        }
    }

    @Test
    void everyMdcFormIsUnsupportedWithoutAddingCaptureOrAllFieldRendering() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            for (String context : List.of("%X{request.id}", "%X{missing:-fallback}", "%X", "%X{request.id,tenant}",
                    "%mdc{request.id}")) {
                Run draft = migrate(provider, "%msg request=" + context + "%n");
                assertEquals(0, draft.status(), draft.err());
                assertTrue(draft.err().contains("MIGRATION: UNSUPPORTED"), draft.err());
                assertTrue(draft.err().contains(context), draft.err());
                assertFalse(draft.out().contains("{fields}"), draft.out());
                var config = LogyardConfigLoader.parse(draft.out(), "migrated.toml", directory, Map.of());
                assertEquals(List.of(), config.context().mdc());
                Run strict = migrate(provider, "%msg request=" + context + "%n", "--strict");
                assertEquals(3, strict.status(), strict.err());
                assertEquals("", strict.out());
            }
        }
    }

    @Test
    void lossyAndUnsupportedConversionsRefuseBeforeCreatingAnOutput() throws Exception {
        Path destination = directory.resolve("result.toml");
        for (String provider : List.of("logback", "log4j2")) {
            for (String pattern : List.of("%-5level %msg%n", "%msg %X{request.id}%n")) {
                Run result = migrate(provider, pattern, "--output", destination.toString(), "--strict");
                assertEquals(3, result.status(), result.err());
                assertEquals("", result.out());
                assertFalse(Files.exists(destination));
                assertTrue(result.err().contains(pattern.contains("%X") ? "UNSUPPORTED" : "LOSSY"), result.err());
            }
        }
        Files.writeString(destination, "existing");
        Run refused = migrate("logback", "%msg %X%n", "--strict", "--output", destination.toString());
        assertEquals(3, refused.status());
        assertEquals("existing", Files.readString(destination));
    }

    @Test
    void literalSpacesAndBracesSurviveTranslationWithoutExtraFields() {
        assertEquals("  {level}  {{approved}}  ",
                Log4j2PatternTranslator.translate("  %level  {approved}  %n").template());
        assertEquals("  {level}  {{approved}}  ",
                LogbackPatternTranslator.translate("  %level  {approved}  %n").template());
    }

    @Test
    void unexaminedXmlCannotBeReportedAsExact() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            String xml = fixture(provider, "%msg%n").replace("name=\"C\"", "name=\"C\" follow=\"true\"");
            Path input = directory.resolve("unknown.xml");
            Files.writeString(input, xml);
            Run result = run("migrate-" + provider, input.toString(), "--strict");
            assertEquals(3, result.status());
            assertTrue(result.err().contains("follow"), result.err());
            assertTrue(result.out().isEmpty());
        }
    }

    @Test
    void strictIsAValuelessSwitchAndRejectsDuplicateOrMisplacedOptions() throws Exception {
        assertEquals(0, migrate("logback", "%msg%n", "--strict").status());
        assertEquals(2, migrate("logback", "%msg%n", "--strict", "--strict").status());
        assertEquals(2, migrate("logback", "%msg%n", "--output", "--strict").status());
        assertEquals(2, run("validate", "--strict").status());
    }

    private Run migrate(String provider, String pattern, String... options) throws Exception {
        Path input = directory.resolve(provider + ".xml");
        Files.writeString(input, fixture(provider, pattern));
        String[] args = new String[options.length + 2];
        args[0] = "migrate-" + provider;
        args[1] = input.toString();
        System.arraycopy(options, 0, args, 2, options.length);
        return run(args);
    }

    private static String fixture(String provider, String pattern) {
        return provider.equals("logback") ? """
                <configuration><appender name="C" class="ch.qos.logback.core.ConsoleAppender">
                  <encoder><pattern>%s</pattern></encoder>
                </appender><root level="INFO"><appender-ref ref="C"/></root></configuration>
                """.formatted(pattern) : """
                <Configuration><Appenders><Console name="C"><PatternLayout pattern="%s"/></Console></Appenders>
                <Loggers><Root level="INFO"><AppenderRef ref="C"/></Root></Loggers></Configuration>
                """.formatted(pattern);
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
