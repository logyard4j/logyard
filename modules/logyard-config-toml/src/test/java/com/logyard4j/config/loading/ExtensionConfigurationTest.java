package com.logyard4j.config.loading;

import com.logyard4j.config.ConfigurationException;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.encoding.JsonEncoderConfig;
import com.logyard4j.config.encoding.JsonProfileConfig;
import com.logyard4j.config.formatting.TemplateFormatterConfig;
import com.logyard4j.config.output.CustomOutputConfig;
import com.logyard4j.config.processing.RateLimitFilterConfig;
import com.logyard4j.config.processing.SamplingFilterConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExtensionConfigurationTest {
    @Test
    void parsesProfilesExtensionsAndExplicitProcessors() {
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

        LogyardConfig config = LogyardConfigLoader.parse(text, "extensions.toml", Path.of("."), Map.of());

        assertEquals(48L, config.aggregateDeliverySlots());
        assertEquals("{level} {message} {fields}", ((TemplateFormatterConfig) config.formatters().get("line")).template());
        JsonProfileConfig profile = config.jsonProfiles().get("audit");
        assertEquals("compact", profile.preset());
        assertEquals("flatten", profile.attributes().mode());
        assertEquals("request_id", profile.attributes().rename().get("request.id"));
        assertEquals("audit", ((JsonEncoderConfig) config.encoders().get("audit")).profile());
        assertEquals("secret", config.enrichers().get("platform").providerReference().configuration().string("auth.token"));
        assertEquals(Boolean.TRUE, config.enrichers().get("platform").providerReference().configuration().booleanValue("enabled"));
        assertEquals(0.25d, ((SamplingFilterConfig) config.filters().get("sample")).probability());
        assertEquals(64, ((RateLimitFilterConfig) config.filters().get("limit")).maxKeys());
        assertEquals(List.of("sample", "limit", "policy"), config.rootLogger().filters());
        CustomOutputConfig custom = (CustomOutputConfig) config.outputs().get("custom");
        assertEquals("line", custom.formatter());
        assertEquals("audit", custom.encoder());
        assertEquals("secret", custom.providerReference().configuration().string("token"));
    }

    @Test
    void rejectsDanglingReferencesAndUnsafeDefinitions() {
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
                template = "{unknown}"
                [outputs.console]
                type = "console"
                """, "bad-template.toml", "unknown placeholder");
        expectFailure("""
                schema = 1
                [filters.bad]
                type = "sampling"
                key = "attribute:bad\\nkey"
                [outputs.console]
                type = "console"
                """, "bad-sampling-key.toml", "sampling key must be");
    }

    private static void expectFailure(String text, String source, String expectedMessage) {
        ConfigurationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ConfigurationException.class,
                () -> LogyardConfigLoader.parse(text, source, Path.of("."), Map.of()));
        assertTrue(failure.getMessage().contains(expectedMessage));
    }
}
