package com.zsumz.logyard.config.schema;

import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the declared schema vocabulary and the decoder behavior from drifting apart. */
public final class ConfigSchemaCoverageTest {
    private static final String COMPLETE_CONFIG = """
            schema = 1
            [service]
            name = "orders"
            namespace = "commerce"
            version = "1.2.3"
            environment = "test"
            instance_id = "node-1"
            [resource.attributes]
            "deployment.region" = "local"
            [runtime]
            shutdown_timeout = "3s"
            internal_status = "warn"
            watch = false
            reload_debounce = "250ms"
            [context]
            trace = true
            mdc = ["request.id"]
            baggage = ["tenant.id"]
            redact = ["password"]
            [delivery]
            mode = "async"
            capacity = 64
            [delivery.overflow]
            trace = "drop"
            debug = "drop"
            info = "drop"
            warn = { action = "block", timeout = "2ms" }
            error = "stderr"
            [loggers]
            root = { level = "info", outputs = ["console"], enrich = ["tenant"], filters = ["sampled"] }
            "com.example" = { level = "debug", outputs = ["archive"], enrich = [], filters = ["limited"] }
            [outputs.console]
            type = "console"
            min_level = "trace"
            stream = "stderr"
            formatter = "human"
            color = { mode = "auto", capability = "auto", theme = "custom-theme" }
            exception = { style = "compact", common_frames = "collapse" }
            delivery = { mode = "async", capacity = 32 }
            [outputs.feed]
            type = "stream"
            stream = "stdout"
            encoder = "wire"
            flush = "25ms"
            [outputs.archive]
            type = "file"
            path = "logs/app.jsonl"
            buffer = "64KiB"
            flush = "1s"
            append = true
            encoder = "wire"
            rotate = { size = "32MiB", keep = 5, compression = "gzip" }
            [outputs.forwarder]
            type = "custom"
            provider = "forwarder"
            implementation = "com.example.ForwarderSink"
            [outputs.forwarder.config]
            target = "upstream"
            [formatters.human]
            type = "template"
            template = "{timestamp} {level} {logger} {message} {fields}"
            [formatters.branded]
            type = "custom"
            provider = "branding"
            [formatters.branded.config]
            label = "orders"
            [encoders.wire]
            type = "json"
            profile = "shaped"
            [encoders.packed]
            type = "custom"
            provider = "packing"
            [encoders.packed.config]
            frame = "length"
            [json_profiles.shaped]
            preset = "logyard"
            drop = ["thread"]
            [json_profiles.shaped.rename]
            body = "msg"
            [json_profiles.shaped.attributes]
            mode = "flatten"
            prefix = "app."
            include = ["order.*"]
            exclude = ["order.secret"]
            [json_profiles.shaped.attributes.rename]
            "order.id" = "order_id"
            [enrichers.tenant]
            provider = "tenant"
            implementation = "com.example.TenantEnricher"
            [enrichers.tenant.config]
            name = "north"
            [filters.sampled]
            type = "sampling"
            probability = 0.5
            key = "event-instance"
            seed = 42
            [filters.limited]
            type = "rate_limit"
            permits_per_second = 100.0
            burst = 50
            key = "logger"
            max_keys = 256
            [filters.gated]
            type = "custom"
            provider = "gate"
            [filters.gated.config]
            allow = "checkout"
            [themes.custom-theme]
            timestamp = { fg = "cyan" }
            logger = { fg = "blue" }
            thread = { dim = true }
            event = { bold = true }
            message = { fg = "white" }
            field_key = { italic = true }
            field_value = { fg = "green" }
            punctuation = { dim = true }
            exception = { fg = "red", bg = "black", underline = true }
            stack_frame = { dim = true }
            [themes.custom-theme.level]
            trace = { dim = true }
            debug = { fg = "magenta" }
            info = { fg = "green" }
            warn = { fg = "yellow" }
            error = { fg = "red", bold = true }
            [profiles.prod.delivery]
            capacity = 128
            """;

    @Test
    void everyDeclaredSchemaKeyIsExercisedAndAccepted() {
        LogyardConfig config =
                LogyardConfigLoader.parse(COMPLETE_CONFIG, "complete.toml", Path.of("."), Map.of());
        if (config.outputs().size() != 4 || config.filters().size() != 3 || config.themes().size() != 1) {
            throw new AssertionError("complete configuration did not decode all sections");
        }
        for (Map.Entry<String, Set<String>> table : ConfigSchema.describe().entrySet()) {
            for (String key : table.getValue()) {
                if (!Pattern.compile("(?m)^(\\s*)\"?" + Pattern.quote(key) + "\"?\\s*=|"
                                + "^\\[[^\\]]*\\b" + Pattern.quote(key) + "\\b[^\\]]*\\]|"
                                + "\\{[^}]*\\b" + Pattern.quote(key) + "\\s*=")
                        .matcher(COMPLETE_CONFIG).find()) {
                    throw new AssertionError(
                            "schema key '" + key + "' of table '" + table.getKey()
                                    + "' is not exercised by the complete configuration");
                }
            }
        }
    }

    @Test
    void schemaLookupResolvesDottedDynamicNames() {
        expectKeys("loggers.com.example.checkout", "level", "outputs");
        expectKeys("outputs.my.output.rotate", "size", "keep", "compression");
        expectKeys("themes.my.theme.level.warn", "fg", "bold");
        expectKeys("delivery.overflow.warn", "action", "timeout");
        if (!ConfigSchema.keysFor("enrichers.tenant.config").isEmpty()) {
            throw new AssertionError("provider config tables must be free-form");
        }
        if (!ConfigSchema.keysFor("no.such.table").isEmpty()) {
            throw new AssertionError("undeclared tables must resolve to no keys");
        }
    }

    private static void expectKeys(String path, String... keys) {
        Set<String> resolved = ConfigSchema.keysFor(path);
        for (String key : keys) {
            if (!resolved.contains(key)) {
                throw new AssertionError("expected key '" + key + "' for table '" + path + "' but found " + resolved);
            }
        }
    }
}
