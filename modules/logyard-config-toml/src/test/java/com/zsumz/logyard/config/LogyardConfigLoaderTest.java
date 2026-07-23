package com.zsumz.logyard.config;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.api.spi.ProviderConfigurationSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    @Test
    void parsesBatchEightExtensionsProfilesAndExplicitProcessors() {
        String text = """
                schema = 1
                [service]
                name = "orders"
                [delivery]
                mode = "async"
                capacity = 16

                [formatters.line]
                type = "template"
                template = "{level} {message} {fields}"

                [json_profiles.audit]
                preset = "compact"
                rename = { logger = "source" }
                drop = ["message_template"]
                [json_profiles.audit.attributes]
                mode = "flatten"
                prefix = "field."
                include = ["request.id"]
                rename = { "request.id" = "request_id" }

                [encoders.audit]
                type = "json"
                profile = "audit"

                [enrichers.platform]
                provider = "company-platform"
                implementation = "com.acme.PlatformProvider"
                config = { auth = { token = "${TOKEN:-secret}" }, enabled = true }

                [filters.sample]
                type = "sampling"
                probability = 0.25
                key = "trace"
                seed = 17

                [filters.limit]
                type = "rate_limit"
                permits_per_second = 12.5
                burst = 20
                key = "attribute:tenant.id"
                max_keys = 64

                [filters.policy]
                type = "custom"
                provider = "company-policy"
                config = { allow = true }

                [loggers]
                root = { level = "info", outputs = ["console", "json", "custom"], enrich = ["platform"], filters = ["sample", "limit", "policy"] }

                [outputs.console]
                type = "console"
                formatter = "line"

                [outputs.json]
                type = "stream"
                encoder = "audit"

                [outputs.custom]
                type = "custom"
                provider = "company-output"
                implementation = "com.acme.OutputProvider"
                formatter = "line"
                encoder = "audit"
                config = { destination = "memory", token = "${TOKEN:-secret}" }
                """;

        LogyardConfig config = LogyardConfigLoader.parse(text, "batch8.toml", Path.of("."), Map.of());

        equal(16L * 3L, config.aggregateDeliverySlots());
        equal("{level} {message} {fields}",
                ((TemplateFormatterConfig) config.formatters().get("line")).template());
        JsonProfileConfig profile = config.jsonProfiles().get("audit");
        equal("compact", profile.preset());
        equal("flatten", profile.attributes().mode());
        equal("request_id", profile.attributes().rename().get("request.id"));
        equal("audit", ((JsonEncoderConfig) config.encoders().get("audit")).profile());
        equal("secret", config.enrichers().get("platform")
                .providerReference().configuration().string("auth.token"));
        equal(Boolean.TRUE, config.enrichers().get("platform")
                .providerReference().configuration().booleanValue("enabled"));
        equal(0.25d, ((SamplingFilterConfig) config.filters().get("sample")).probability());
        equal(64, ((RateLimitFilterConfig) config.filters().get("limit")).maxKeys());
        equal(java.util.List.of("sample", "limit", "policy"), config.rootLogger().filters());
        CustomOutputConfig custom = (CustomOutputConfig) config.outputs().get("custom");
        equal("line", custom.formatter());
        equal("audit", custom.encoder());
        equal("secret", custom.providerReference().configuration().string("token"));
    }

    @Test
    void rejectsDanglingBatchEightReferencesAndUnsafeDefinitions() {
        expectFailure("""
                schema = 1
                [loggers]
                root = { outputs = ["console"] }
                [outputs.console]
                type = "console"
                formatter = "missing"
                """, "missing-formatter.toml", "unknown formatter");

        expectFailure("""
                schema = 1
                [json_profiles.logyard]
                preset = "compact"
                [outputs.console]
                type = "console"
                """, "shadow-profile.toml", "must not shadow built-in profile");

        expectFailure("""
                schema = 1
                [delivery]
                mode = "sync"
                capacity = 16
                [outputs.remote]
                type = "custom"
                provider = "remote"
                """, "sync-custom.toml", "must use asynchronous delivery");

        expectFailure("""
                schema = 1
                [enrichers.policy]
                provider = "enricher"
                [filters.policy]
                type = "custom"
                provider = "filter"
                [outputs.console]
                type = "console"
                """, "duplicate-processor.toml", "names must be distinct");

        expectFailure("""
                schema = 1
                [formatters.bad]
                type = "template"
                template = "{logger}"
                [outputs.console]
                type = "console"
                """, "bad-template.toml", "must contain {message}");

        expectFailure("""
                schema = 1
                [filters.bad]
                type = "sampling"
                key = "attribute:bad\\nkey"
                [outputs.console]
                type = "console"
                """, "bad-sampling-key.toml", "sampling key must be");
    }

    @Test
    void providerConfigurationRejectsNormalizedKeyCollisions() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(" token", "first");
        values.put("token", "second");
        try {
            new ProviderConfiguration(values);
            throw new AssertionError("normalized provider key collision should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("duplicate normalized"),
                    "diagnostic should report normalized provider key collision");
        }
    }


    @Test
    void providerConfigurationSpecsNormalizeAndRejectCollisions() {
        ProviderConfigurationSpec spec = ProviderConfigurationSpec.of(
                Set.of(" token "), Set.of("token"));
        ProviderConfiguration first = new ProviderConfiguration(Map.of(
                "token", "value", "retries", 3L));
        ProviderConfiguration second = new ProviderConfiguration(Map.of(
                "retries", 3L, "token", "value"));
        spec.validate(new ProviderConfiguration(Map.of("token", "value")));
        equal(Set.of("token"), spec.allowedKeys());
        equal(first, second);
        equal(first.hashCode(), second.hashCode());
        check(!first.toString().contains("value"),
                "provider configuration string must not expose values");

        try {
            ProviderConfigurationSpec.of(Set.of(" token", "token"), Set.of());
            throw new AssertionError("normalized provider spec key collision should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("duplicate normalized"),
                    "diagnostic should report normalized provider spec key collision");
        }

        try {
            new ProviderConfiguration(Map.of("ratio", Double.NaN));
            throw new AssertionError("non-finite provider number should fail");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("must be finite"),
                    "diagnostic should report non-finite provider number");
        }
    }

    public static void main(String[] args) {
        LogyardConfigLoaderTest test = new LogyardConfigLoaderTest();
        test.parsesFrozenSchemaNamedOutputsAndGenericEnrichers();
        test.rejectsUnknownKeysWithSuggestion();
        test.reportsMissingValueCleanly();
        test.rejectsOversizedConfigurationBeforeParsing();
        test.rejectsUnboundedJsonBuffersAndAggregateQueues();
        test.parsesStreamOutputAndDeliveryOverride();
        test.rejectsNonConformingToml();
        test.parsesBatchEightExtensionsProfilesAndExplicitProcessors();
        test.rejectsDanglingBatchEightReferencesAndUnsafeDefinitions();
        test.providerConfigurationRejectsNormalizedKeyCollisions();
        test.providerConfigurationSpecsNormalizeAndRejectCollisions();
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
