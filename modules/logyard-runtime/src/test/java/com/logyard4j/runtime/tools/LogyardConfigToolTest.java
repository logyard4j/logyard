package com.logyard4j.runtime.tools;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies the offline configuration tool commands and their exit codes. */
public final class LogyardConfigToolTest {
    private static final String CONFIG = """
            schema = 1
            [service]
            name = "orders"
            [delivery]
            capacity = 48
            [loggers]
            root = { level = "info", outputs = ["console"] }
            "com.example" = { level = "debug" }
            [outputs.console]
            type = "console"
            [profiles.prod.loggers]
            root = { level = "warn", outputs = ["console"] }
            """;

    private static final String LOG4J2 = """
            <Configuration>
              <Appenders>
                <Console name="Console" target="SYSTEM_ERR">
                  <PatternLayout pattern="%d %p %c - %m%n"/>
                </Console>
                <Syslog name="Remote" host="localhost" port="514"/>
              </Appenders>
              <Loggers>
                <Root level="info"><AppenderRef ref="Console"/></Root>
              </Loggers>
            </Configuration>
            """;

    @Test
    void validateReportsProfilesOverridesAndCounts() throws Exception {
        Path config = write(CONFIG);
        try {
            Run run = run("validate", config.toString());
            equal(0, run.status());
            check(run.out().contains("OK " + config), run.out());
            check(run.out().contains("profile: (none)"), run.out());
            check(run.out().contains("profiles validated: prod"), run.out());
            check(run.out().contains("outputs: 1, logger rules: 2"), run.out());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void validateFailsWithLocatedDiagnosticsAndExitCodeOne() throws Exception {
        Path config = write(CONFIG.replace("capacity = 48", "capactiy = 48"));
        try {
            Run run = run("validate", config.toString());
            equal(1, run.status());
            check(run.err().contains("invalid configuration: " + config + ":5"), run.err());
            check(run.err().contains("did you mean 'capacity'?"), run.err());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void validateSelectsTheRequestedProfile() throws Exception {
        Path config = write(CONFIG);
        try {
            Run run = run("validate", config.toString(), "--profile", "prod");
            equal(0, run.status());
            check(run.out().contains("profile: prod"), run.out());
            Run unknown = run("validate", config.toString(), "--profile", "missing");
            equal(1, unknown.status());
            check(unknown.err().contains("unknown profile 'missing'"), unknown.err());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void explainAnswersKeyLoggerAndFullDumpQuestions() throws Exception {
        Path config = write(CONFIG);
        try {
            Run key = run("explain", config.toString(), "--key", "delivery.capacity");
            equal(0, key.status());
            check(key.out().contains("delivery.capacity = 48"), key.out());
            check(key.out().contains("origin: " + config + ":5"), key.out());

            Run profiled = run(
                    "explain", config.toString(), "--profile", "prod", "--key", "loggers.root.level");
            check(profiled.out().contains("(profile 'prod')"), profiled.out());

            Run logger = run("explain", config.toString(), "--logger", "com.example.checkout.Cart");
            equal(0, logger.status());
            check(logger.out().contains("level: DEBUG  (rule 'com.example'"), logger.out());
            check(logger.out().contains("outputs: [\"console\"]  (root"), logger.out());

            Run dump = run("explain", config.toString());
            equal(0, dump.status());
            check(dump.out().contains("service.name = \"orders\""), dump.out());
            check(dump.out().contains("# " + config + ":3"), dump.out());

            Run unset = run("explain", config.toString(), "--key", "runtime.watch");
            check(unset.out().contains("runtime.watch is not set"), unset.out());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void schemaPrintsTheVocabularyAsJson() {
        Run run = run("schema");
        equal(0, run.status());
        check(run.out().contains("\"tables\""), run.out());
        check(run.out().contains("\"delivery\": [\"capacity\", \"mode\", \"overflow\"]"), run.out());
        check(run.out().trim().startsWith("{") && run.out().trim().endsWith("}"), run.out());
    }

    @Test
    void migrateLog4j2WritesTomlReportsNotesAndRefusesToOverwrite() throws Exception {
        Path input = write(LOG4J2);
        Path output = Path.of(input + ".toml");
        try {
            Run run = run("migrate-log4j2", input.toString(), "--output", output.toString());
            equal(0, run.status());
            check(run.out().contains("wrote " + output), run.out());
            check(run.err().contains("NOTE: appender 'Remote' (<Syslog>)"), run.err());
            String migrated = Files.readString(output);
            check(migrated.contains("[outputs.console]") && migrated.contains("stream = \"stderr\""), migrated);

            Run overwrite = run("migrate-log4j2", input.toString(), "--output", output.toString());
            equal(2, overwrite.status());
            check(overwrite.err().contains("refusing to overwrite existing file"), overwrite.err());
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(input);
        }
    }

    @Test
    void migrateLog4j2ExitsOneWhenTheGeneratedConfigurationIsNotValid() throws Exception {
        Path input = write(LOG4J2.replace(
                "<Syslog name=\"Remote\" host=\"localhost\" port=\"514\"/>",
                "<File name=\"Audit\" fileName=\"${sys:log.dir}/audit.log\"/>"));
        try {
            Run run = run("migrate-log4j2", input.toString());
            equal(1, run.status());
            check(run.err().contains("WARNING: the generated configuration failed validation"), run.err());
            check(run.out().contains("${sys:log.dir}"), run.out());
        } finally {
            Files.deleteIfExists(input);
        }
    }

    @Test
    void usageErrorsExitWithCodeTwo() {
        equal(2, run("migrate-log4j2").status());
        equal(2, run("migrate-log4j2", "/nonexistent/logyard-tool-test.xml").status());
        check(run("help").out().contains("migrate-log4j2 <log4j2.xml> [--output <file>]"), run("help").out());
        equal(2, run("frobnicate").status());
        equal(2, run("validate").status());
        equal(2, run("validate", "one.toml", "--bogus", "x").status());
        equal(2, run("validate", "/nonexistent/logyard-tool-test.toml").status());
        equal(0, run("help").status());
    }

    private record Run(int status, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = LogyardConfigTool.run(
                args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path write(String content) throws Exception {
        Path file = Files.createTempFile("logyard-tool-", ".toml");
        Files.writeString(file, content);
        return file;
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String output) {
        if (!condition) {
            throw new AssertionError("unexpected tool output: " + output);
        }
    }
}
