package com.zsumz.logyard.config.loading;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.config.ConfigurationException;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.JsonStreamOutputConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

public final class LogyardConfigLoaderTest {
    @Test
    void parsesFrozenSchemaNamedOutputsAndGenericEnrichers() {
        String text = """
                schema = 1
                [service]
                name = "orders"
                namespace = "commerce"
                environment = "${ENV:-test}"
                instance_id = "$${LITERAL}"
                [resource.attributes]
                "deployment.region" = "local"
                [delivery]
                mode = "async"
                capacity = 32
                [delivery.overflow]
                warn = { action = "stderr", timeout = "2ms" }
                [context]
                trace = true
                mdc = ["request.id"]
                baggage = ["tenant.id"]
                [enrichers.company-platform]
                provider = "company-platform"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                "org.example" = { enrich = ["company-platform"] }
                [outputs.console]
                type = "console"
                [outputs.console.color]
                mode = "never"
                capability = "auto"
                theme = "ember"
                [outputs.console.exception]
                style = "compact"
                common_frames = "collapse"
                """;
        LogyardConfig config = LogyardConfigLoader.parse(text, "test.toml", Path.of("."), Map.of());
        equal("orders", config.service().name());
        equal("commerce", config.service().namespace());
        equal("test", config.service().environment());
        equal("${LITERAL}", config.service().instanceId());
        equal("local", config.resource().attributes().get("deployment.region"));
        equal(Level.INFO, config.rootLogger().level());
        equal(32, config.delivery().capacity());
        equal(32L, config.aggregateDeliverySlots());
        equal("never", ((ConsoleOutputConfig) config.outputs().get("console")).color().mode());
        equal(java.util.List.of("company-platform"), config.loggers().get("org.example").enrich());
    }

    @Test
    void rejectsUnknownKeysWithSuggestion() {
        String text = """
                schema = 1
                [delivery]
                capactiy = 32
                [outputs.console]
                type = "console"
                """;
        try {
            LogyardConfigLoader.parse(text, "bad.toml", Path.of("."), Map.of());
            throw new AssertionError("unknown key should fail");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("capacity"), "diagnostic should suggest capacity");
        }
    }

    @Test
    void reportsMissingValueCleanly() {
        String text = "schema =";
        try {
            LogyardConfigLoader.parse(text, "bad.toml", Path.of("."), Map.of());
            throw new AssertionError("missing value should fail");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("expected a value"),
                    "diagnostic should explain missing value");
        }
    }

    @Test
    void rejectsOversizedConfigurationBeforeParsing() {
        String text = "x".repeat(LogyardConfigLoader.MAX_CONFIG_BYTES + 1);
        try {
            LogyardConfigLoader.parse(text, "large.toml", Path.of("."), Map.of());
            throw new AssertionError("oversized configuration should fail");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains("exceeds"), "diagnostic should report the size bound");
        }
    }

    @Test
    void rejectsUnboundedJsonBuffersAndAggregateQueues() {
        String oversizedBuffer = """
                schema = 1
                [loggers]
                root = { outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "orders.jsonl"
                buffer = "17MiB"
                """;
        expectFailure(oversizedBuffer, "large-buffer.toml", "16MiB");

        String aggregate = """
                schema = 1
                [delivery]
                capacity = 16777216
                [outputs.a]
                type = "console"
                [outputs.b]
                type = "stream"
                """;
        expectFailure(aggregate, "aggregate.toml", "aggregate delivery capacity");
    }

    @Test
    void parsesStreamOutputAndDeliveryOverride() {
        String text = """
                schema = 1
                [service]
                name = "orders"
                [delivery]
                mode = "async"
                capacity = 64
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "stream"
                stream = "stdout"
                flush = "25ms"
                [outputs.json.delivery]
                capacity = 128
                """;
        LogyardConfig config = LogyardConfigLoader.parse(text, "stream.toml", Path.of("."), Map.of());
        JsonStreamOutputConfig stream = (JsonStreamOutputConfig) config.outputs().get("json");
        equal("stdout", stream.stream());
        equal(Duration.ofMillis(25), stream.flushInterval());
        equal(128, config.deliveryFor(stream).capacity());
        equal(128L, config.aggregateDeliverySlots());
    }

    @Test
    void rejectsNonConformingToml() {
        expectFailure("""
                schema = 01
                [outputs.console]
                type = "console"
                """, "leading-zero.toml", "invalid integer");
        expectFailure("""
                schema = 1__0
                [outputs.console]
                type = "console"
                """, "underscore.toml", "invalid integer");
        expectFailure("""
                schema = 1
                [outputs.console]
                type = "console"
                [outputs.console]
                """, "duplicate-table.toml", "already defined");
    }

    private static void expectFailure(String text, String source, String message) {
        try {
            LogyardConfigLoader.parse(text, source, Path.of("."), Map.of());
            throw new AssertionError("configuration should fail: " + source);
        } catch (ConfigurationException expected) {
            check(expected.getMessage().contains(message),
                    "diagnostic should contain '" + message + "' but was " + expected.getMessage());
        }
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
