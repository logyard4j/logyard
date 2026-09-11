package com.logyard4j.runtime.tools;

import com.logyard4j.api.Level;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.config.output.ConsoleOutputConfig;
import com.logyard4j.config.output.JsonFileOutputConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the Log4j2-to-Logyard migration output, semantics, and loss report. */
public final class Log4j2MigrationTest {
    private static final String LOG4J2 = """
            <Configuration status="warn" monitorInterval="30" name="checkout">
              <Properties>
                <Property name="LOG_DIR">${env:LOG_HOME:-logs}</Property>
                <Property name="ARCHIVE">${sys:app.home}/archive</Property>
              </Properties>
              <Appenders>
                <Console name="Console" target="SYSTEM_OUT">
                  <PatternLayout pattern="%d{ISO8601} [%t] %-5level %logger{36} - %msg%n%throwable"/>
                </Console>
                <RollingFile name="Rolling" fileName="${LOG_DIR}/app.log"
                             filePattern="${ARCHIVE}/app-%d{yyyy-MM-dd}-%i.log.gz">
                  <JsonTemplateLayout eventTemplateUri="classpath:EcsLayout.json"/>
                  <Policies>
                    <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
                    <SizeBasedTriggeringPolicy size="10 MB"/>
                  </Policies>
                  <DefaultRolloverStrategy max="7"/>
                  <ThresholdFilter level="WARN" onMatch="ACCEPT" onMismatch="DENY"/>
                </RollingFile>
                <Async name="AsyncRolling" bufferSize="512">
                  <AppenderRef ref="Rolling"/>
                </Async>
                <Socket name="Remote" host="collector.internal" port="9500"/>
              </Appenders>
              <Loggers>
                <Root level="info">
                  <AppenderRef ref="Console"/>
                  <AppenderRef ref="AsyncRolling"/>
                </Root>
                <Logger name="com.example.checkout" level="debug"/>
                <Logger name="org.hibernate" level="OFF" additivity="false">
                  <AppenderRef ref="Rolling"/>
                </Logger>
                <Logger name="com.example.audit" level="FATAL">
                  <AppenderRef ref="Console"/>
                </Logger>
                <AsyncLogger name="com.example.hot" level="trace"/>
              </Loggers>
            </Configuration>
            """;

    @Test
    void migratesARealisticLog4j2ConfigurationToValidLogyardToml() throws Exception {
        Log4j2Migration.Result result = Log4j2Migration.migrate(LOG4J2.getBytes(StandardCharsets.UTF_8));
        check(result.valid(), "migrated configuration should validate: " + result.validationError());

        LogyardConfig config = LogyardConfigLoader.parse(result.toml(), "migrated.toml", Path.of("."), Map.of());
        ConsoleOutputConfig console = (ConsoleOutputConfig) config.outputs().get("console");
        equal("stdout", console.stream());
        JsonFileOutputConfig file = (JsonFileOutputConfig) config.outputs().get("rolling");
        check(file.path().endsWith(Path.of("logs", "app.log")), "property-substituted path: " + file.path());
        equal(10L * 1024 * 1024, file.rotation().sizeBytes());
        equal(7, file.rotation().keep());
        equal("gzip", file.rotation().compress());
        equal(Level.WARN, file.minimumLevel());
        equal(512, config.delivery().capacity());
        check(result.toml().contains("watch = true"), "monitorInterval became runtime.watch");
        check(result.toml().contains("name = \"checkout\""), "configuration name became service.name");
    }

    @Test
    void mapsLoggerLevelsAppenderReferencesAndTheConsoleTemplate() throws Exception {
        Log4j2Migration.Result result = Log4j2Migration.migrate(LOG4J2.getBytes(StandardCharsets.UTF_8));
        LogyardConfig config = LogyardConfigLoader.parse(result.toml(), "migrated.toml", Path.of("."), Map.of());

        equal(Level.INFO, config.rootLogger().level());
        equal(List.of("console", "rolling"), config.rootLogger().outputs());
        equal(Level.DEBUG, config.loggers().get("com.example.checkout").level());
        equal(Level.ERROR, config.loggers().get("org.hibernate").level());
        equal(List.of(), config.loggers().get("org.hibernate").outputs());
        equal(Level.ERROR, config.loggers().get("com.example.audit").level());
        equal(List.of("console"), config.loggers().get("com.example.audit").outputs());
        equal(Level.TRACE, config.loggers().get("com.example.hot").level());

        String template = result.toml().lines()
                .filter(line -> line.startsWith("template"))
                .findFirst()
                .orElseThrow();
        equal("template = \"{timestamp} [{thread}] {level} {logger} - {message}\"", template);
    }

    @Test
    void reportsEveryConstructThatHasNoEquivalent() throws Exception {
        Log4j2Migration.Result result = Log4j2Migration.migrate(LOG4J2.getBytes(StandardCharsets.UTF_8));
        check(note(result, "time-based rollover"), "time-based rollover note");
        check(note(result, "the sys lookup '${sys:app.home}'"), "system property lookup note");
        check(note(result, "appender 'Remote' (<Socket>) has no Logyard equivalent"), "unmappable appender note");
        check(note(result, "(Log4j2 additivity)"), "additivity note");
        check(note(result, "was unwrapped"), "async unwrap note");
        check(note(result, "structured JSON lines"), "JsonTemplateLayout note");
        check(note(result, "level FATAL maps to error"), "FATAL level note");
        check(note(result, "level OFF"), "OFF level note");
        check(note(result, "<AsyncLogger> was treated as <Logger>"), "async logger note");
        check(note(result, "runtime.internal_status"), "status attribute note");
        check(note(result, "monitorInterval='30'"), "monitor interval note");
        for (String note : result.notes()) {
            check(result.toml().contains("# NOTE: " + note), "note is repeated in the document: " + note);
        }
    }

    @Test
    void acceptsLowercaseElementsAndReportsUnsupportedSections() throws Exception {
        String lowercase = """
                <configuration>
                  <CustomLevels><CustomLevel name="NOTICE" intLevel="450"/></CustomLevels>
                  <Select>
                    <SystemPropertyArbiter propertyName="stage" propertyValue="prod"/>
                  </Select>
                  <appenders>
                    <console name="out">
                      <PatternLayout><Pattern>%p %m%n</Pattern></PatternLayout>
                    </console>
                    <Routing name="routed"/>
                  </appenders>
                  <loggers>
                    <root level="warn"><appenderref ref="out"/></root>
                  </loggers>
                </configuration>
                """;
        Log4j2Migration.Result result = Log4j2Migration.migrate(lowercase.getBytes(StandardCharsets.UTF_8));
        check(result.valid(), "lowercase configuration should validate: " + result.validationError());
        LogyardConfig config = LogyardConfigLoader.parse(result.toml(), "migrated.toml", Path.of("."), Map.of());
        equal(Level.WARN, config.rootLogger().level());
        equal(List.of("out"), config.rootLogger().outputs());
        equal("stdout", ((ConsoleOutputConfig) config.outputs().get("out")).stream());
        check(result.toml().contains("template = \"{level} {message}\""), result.toml());
        check(note(result, "five fixed levels"), "custom levels note");
        check(note(result, "configuration arbiter"), "arbiter note");
        check(note(result, "(<Routing>) has no Logyard equivalent"), "routing appender note");
    }

    @Test
    void lookupsLogyardCannotEvaluateAreReportedAndFailValidation() throws Exception {
        String systemProperty = """
                <Configuration>
                  <Appenders>
                    <File name="Audit" fileName="${sys:log.dir}/audit.log" append="false"/>
                  </Appenders>
                  <Loggers><Root level="info"><AppenderRef ref="Audit"/></Root></Loggers>
                </Configuration>
                """;
        Log4j2Migration.Result result = Log4j2Migration.migrate(systemProperty.getBytes(StandardCharsets.UTF_8));
        check(note(result, "the sys lookup '${sys:log.dir}'"), "system property lookup note");
        check(result.toml().contains("${sys:log.dir}"), "the lookup is left in place verbatim");
        check(result.toml().contains("append = false"), "append attribute");
        check(!result.valid(), "a lookup Logyard cannot evaluate must not be reported as valid");
        check(result.validationError().contains("invalid environment name"), result.validationError());
    }

    @Test
    void doctypeDeclarationsAreRejected() {
        String hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE Configuration [<!ENTITY x SYSTEM "file:///etc/hosts">]>
                <Configuration>&x;</Configuration>
                """;
        try {
            Log4j2Migration.migrate(hostile.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("doctype should be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("XML"), expected.getMessage());
        } catch (Exception unexpected) {
            throw new AssertionError("expected IllegalArgumentException", unexpected);
        }
    }

    @Test
    void aDocumentThatIsNotALog4j2ConfigurationIsRejected() {
        String logback = """
                <configuration scan="true">
                  <root level="INFO"/>
                </configuration>
                """.replace("configuration", "included");
        try {
            Log4j2Migration.migrate(logback.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("wrong root element should be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("root element must be <Configuration>"), expected.getMessage());
            check(expected.getMessage().contains("<included>"), expected.getMessage());
        } catch (Exception unexpected) {
            throw new AssertionError("expected IllegalArgumentException", unexpected);
        }
    }

    private static boolean note(Log4j2Migration.Result result, String fragment) {
        return result.notes().stream().anyMatch(note -> note.contains(fragment));
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
