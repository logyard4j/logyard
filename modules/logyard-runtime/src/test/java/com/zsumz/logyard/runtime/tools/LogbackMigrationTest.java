package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the Logback-to-Logyard migration output, semantics, and loss report. */
public final class LogbackMigrationTest {
    private static final String LOGBACK = """
            <configuration scan="true" scanPeriod="30 seconds">
              <contextName>checkout</contextName>
              <property name="LOG_DIR" value="logs"/>
              <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
                <target>System.out</target>
                <encoder>
                  <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
                </encoder>
              </appender>
              <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
                <file>${LOG_DIR}/app.log</file>
                <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                  <fileNamePattern>${LOG_DIR}/app-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
                  <maxFileSize>10MB</maxFileSize>
                  <maxHistory>7</maxHistory>
                  <totalSizeCap>1GB</totalSizeCap>
                </rollingPolicy>
                <encoder><pattern>%d %level %logger %msg%n</pattern></encoder>
              </appender>
              <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
                <appender-ref ref="FILE"/>
                <queueSize>512</queueSize>
              </appender>
              <root level="INFO">
                <appender-ref ref="CONSOLE"/>
                <appender-ref ref="ASYNC"/>
              </root>
              <logger name="com.example.checkout" level="DEBUG"/>
              <logger name="org.hibernate" level="OFF" additivity="false">
                <appender-ref ref="FILE"/>
              </logger>
              <springProfile name="prod">
                <root level="WARN"/>
                <logger name="com.example" level="INFO"/>
              </springProfile>
            </configuration>
            """;

    @Test
    void migratesARealisticLogbackConfigurationToValidLogyardToml() throws Exception {
        LogbackMigration.Result result = LogbackMigration.migrate(LOGBACK.getBytes(StandardCharsets.UTF_8));
        check(result.valid(), "migrated configuration should validate: " + result.validationError());

        LogyardConfig config = LogyardConfigLoader.parse(result.toml(), "migrated.toml", Path.of("."), Map.of());
        ConsoleOutputConfig console = (ConsoleOutputConfig) config.outputs().get("console");
        equal("stdout", console.stream());
        JsonFileOutputConfig file = (JsonFileOutputConfig) config.outputs().get("file");
        check(file.path().endsWith(Path.of("logs", "app.log")), "property-substituted path: " + file.path());
        equal(10L * 1024 * 1024, file.rotation().sizeBytes());
        equal(7, file.rotation().keep());
        equal("gzip", file.rotation().compress());
        equal(512, config.delivery().capacity());
        equal(Level.INFO, config.rootLogger().level());
        equal(List.of("console", "file"), config.rootLogger().outputs());
        equal(Level.DEBUG, config.loggers().get("com.example.checkout").level());
        equal(Level.ERROR, config.loggers().get("org.hibernate").level());
        equal(List.of(), config.loggers().get("org.hibernate").outputs());

        String template = result.toml().lines()
                .filter(line -> line.startsWith("template"))
                .findFirst()
                .orElseThrow();
        check(template.contains("{timestamp} [{thread}] {level} {logger} - {message}"), template);

        check(result.toml().contains("[profiles.prod.loggers]"), "spring profile section");
        check(result.toml().contains("root = { level = \"warn\" }"), "profile root rule");
        check(note(result, "time-based rollover"), "time-based rotation note");
        check(note(result, "structured JSON lines"), "file pattern note");
        check(note(result, "level OFF"), "level OFF note");
        check(note(result, "asynchronous"), "async unwrap note");
        check(note(result, "totalSizeCap"), "size cap note");
    }

    @Test
    void migratedProfilesActuallySelect() throws Exception {
        LogbackMigration.Result result = LogbackMigration.migrate(LOGBACK.getBytes(StandardCharsets.UTF_8));
        var loaded = LogyardConfigLoader.parseDetailed(
                result.toml(), "migrated.toml", Path.of("."), Map.of(),
                new com.zsumz.logyard.config.loading.overlay.ConfigOverlays("prod", List.of()));
        equal(Level.WARN, loaded.config().rootLogger().level());
        equal(Level.INFO, loaded.config().loggers().get("com.example").level());
    }

    @Test
    void doctypeDeclarationsAreRejected() {
        String hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE configuration [<!ENTITY x SYSTEM "file:///etc/hosts">]>
                <configuration>&x;</configuration>
                """;
        try {
            LogbackMigration.migrate(hostile.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("doctype should be rejected");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("XML"), expected.getMessage());
        } catch (Exception unexpected) {
            throw new AssertionError("expected IllegalArgumentException", unexpected);
        }
    }

    @Test
    void unconvertibleAppendersAreReportedNotSilentlyDropped() throws Exception {
        String syslog = """
                <configuration>
                  <appender name="SYSLOG" class="ch.qos.logback.classic.net.SyslogAppender">
                    <syslogHost>localhost</syslogHost>
                  </appender>
                  <root level="INFO"><appender-ref ref="SYSLOG"/></root>
                </configuration>
                """;
        LogbackMigration.Result result = LogbackMigration.migrate(syslog.getBytes(StandardCharsets.UTF_8));
        check(note(result, "no Logyard equivalent"), "unmappable appender note");
        check(note(result, "does not resolve"), "dangling reference note");
        check(result.valid(), "fallback console output keeps the configuration valid");
    }

    private static boolean note(LogbackMigration.Result result, String fragment) {
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
