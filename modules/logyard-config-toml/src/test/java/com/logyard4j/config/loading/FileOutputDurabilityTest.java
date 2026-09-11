package com.logyard4j.config.loading;

import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.output.JsonFileOutputConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the time-based rotation limit and the opt-in durable-flush switch of file outputs. */
public final class FileOutputDurabilityTest {
    @Test
    void sizeOnlyRotationLeavesTheIntervalAbsent() {
        JsonFileOutputConfig output = file("rotate = { size = \"32MiB\", keep = 3 }");
        check(output.rotation().sizeBytes() == 32L * 1_024 * 1_024, "declared size", output);
        check(output.rotation().interval() == null, "absent interval", output);
    }

    @Test
    void intervalOnlyRotationKeepsTheDefaultSize() {
        JsonFileOutputConfig output = file("rotate = { interval = \"90s\" }");
        check(output.rotation().sizeBytes() == 1L << 30, "default 1GiB size", output);
        check(Duration.ofSeconds(90).equals(output.rotation().interval()), "decoded interval", output);
        check(output.rotation().keep() == 10, "default retention", output);
    }

    @Test
    void sizeAndIntervalRotationDecodeTogether() {
        JsonFileOutputConfig output =
                file("rotate = { size = \"1MiB\", keep = 2, compression = \"gzip\", interval = \"5m\" }");
        check(output.rotation().sizeBytes() == 1_048_576L, "declared size", output);
        check(Duration.ofMinutes(5).equals(output.rotation().interval()), "decoded interval", output);
        check("gzip".equals(output.rotation().compress()), "declared compression", output);
    }

    @Test
    void intervalAcceptsHourAndDayUnits() {
        check(
                Duration.ofHours(6).equals(file("rotate = { interval = \"6h\" }").rotation().interval()),
                "hours unit",
                null);
        check(
                Duration.ofDays(7).equals(file("rotate = { interval = \"7d\" }").rotation().interval()),
                "days unit",
                null);
        check(
                Duration.ofDays(365).equals(file("rotate = { interval = \"365d\" }").rotation().interval()),
                "maximum interval",
                null);
        check(
                Duration.ofSeconds(1).equals(file("rotate = { interval = \"1000ms\" }").rotation().interval()),
                "minimum interval",
                null);
    }

    @Test
    void intervalBelowOneSecondIsRejectedWithALocatedDiagnostic() {
        String message = failure("rotate = { interval = \"999ms\" }");
        check(message.startsWith("bad.toml:5: "), "line of the rotate assignment", message);
        check(message.contains("outputs.app.rotate.interval"), "path of the rejected key", message);
        check(message.contains("must be between 1s and 365d"), "bounds diagnostic", message);
    }

    @Test
    void intervalAboveOneYearIsRejectedWithALocatedDiagnostic() {
        String message = failure("rotate = { interval = \"366d\" }");
        check(message.startsWith("bad.toml:5: "), "line of the rotate assignment", message);
        check(message.contains("outputs.app.rotate.interval"), "path of the rejected key", message);
        check(message.contains("must be between 1s and 365d"), "bounds diagnostic", message);
    }

    @Test
    void unparsableIntervalIsRejectedWithALocatedDiagnostic() {
        String message = failure("rotate = { interval = \"weekly\" }");
        check(message.startsWith("bad.toml:5: "), "line of the rotate assignment", message);
        check(message.contains("outputs.app.rotate.interval"), "path of the rejected key", message);
    }

    @Test
    void fsyncDefaultsToOffAndCanBeEnabled() {
        check(!file("append = true").fsync(), "durable flushing off by default", null);
        check(file("fsync = true").fsync(), "durable flushing enabled", null);
        check(!file("fsync = false").fsync(), "durable flushing explicitly disabled", null);
    }

    @Test
    void fsyncRejectsNonBooleanValuesWithALocatedDiagnostic() {
        String message = failure("fsync = \"yes\"");
        check(message.startsWith("bad.toml:5: "), "line of the fsync assignment", message);
        check(message.contains("outputs.app.fsync"), "path of the rejected key", message);
        check(message.contains("expected true or false"), "type diagnostic", message);
    }

    private static JsonFileOutputConfig file(String extraKey) {
        LogyardConfig config = LogyardConfigLoader.parse(text(extraKey), "good.toml", Path.of("."), Map.of());
        return (JsonFileOutputConfig) config.outputs().get("app");
    }

    private static String failure(String extraKey) {
        try {
            LogyardConfigLoader.parse(text(extraKey), "bad.toml", Path.of("."), Map.of());
            throw new AssertionError("configuration should fail");
        } catch (ConfigurationException expected) {
            return expected.getMessage();
        }
    }

    private static String text(String extraKey) {
        return """
                schema = 1
                [outputs.app]
                type = "file"
                path = "app.jsonl"
                %s
                """.formatted(extraKey);
    }

    private static void check(boolean condition, String expectation, Object detail) {
        if (!condition) {
            throw new AssertionError("expected " + expectation + " but found " + detail);
        }
    }
}
