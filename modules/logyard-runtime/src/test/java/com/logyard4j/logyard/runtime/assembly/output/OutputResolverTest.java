package com.logyard4j.logyard.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderProvider;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;
import com.logyard4j.logyard.output.console.style.ConsoleTheme;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import com.logyard4j.logyard.runtime.extension.ExtensionRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class OutputResolverTest {
    private static final ExtensionRegistry NO_EXTENSIONS = new ExtensionRegistry(Map.of(), Map.of(), Map.of(), Map.of());

    @Test
    void resolvesTemplateFormatterAndBuiltInTheme() {
        LogyardConfig config = config();

        TextFormatter formatter = FormatterResolver.resolve(config, "line", NO_EXTENSIONS);
        ConsoleTheme theme = FormatterResolver.consoleTheme(config, "mono");

        assertEquals("INFO hello", formatter.format(event()));
        assertEquals("mono", theme.name());
    }

    @Test
    void resolvesResourceAttributesAndConfiguredJsonEncoder() {
        LogyardConfig config = config();
        ResourceAttributes resource = EncoderResolver.resource(config);
        EventEncoder encoder = EncoderResolver.resolve(config, "record", resource, NO_EXTENSIONS);

        String encoded = encoder.encode(event());

        assertEquals("test-service", resource.values().get("service.name"));
        assertEquals("application/json; charset=utf-8", encoder.mediaType());
        assertTrue(encoded.contains("\"msg\":\"hello\""));
        assertTrue(encoded.contains("\"service.name\":\"test-service\""));
    }

    @Test
    void runtimeResolvedProviderEncoderUsesTheSharedRecordBoundary() {
        AtomicReference<String> encoded = new AtomicReference<>("{}");
        EventEncoderProvider provider = new EventEncoderProvider() {
            @Override
            public String name() {
                return "contract";
            }

            @Override
            public EventEncoder create(ProviderConfiguration configuration) {
                return event -> encoded.get();
            }
        };
        LogyardConfig config = LogyardConfigLoader.parse("""
                schema = 1
                [service]
                name = "test-service"
                [delivery]
                mode = "sync"
                [encoders.contract]
                type = "custom"
                provider = "contract"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                """, "provider-encoder.toml", Path.of("."), Map.of());
        ExtensionRegistry extensions = new ExtensionRegistry(Map.of(), Map.of("contract", provider), Map.of(), Map.of());
        EventEncoder resolved = EncoderResolver.resolve(config, "contract", EncoderResolver.resource(config), extensions);

        for (String invalid : new String[] {
                null,
                "first\nsecond",
                "first\rsecond",
                "x".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES + 1),
                "\u00e9".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES / 2 + 1)
        }) {
            encoded.set(invalid);
            assertThrows(RuntimeException.class, () -> resolved.encode(event()));
        }
        encoded.set("{\"valid\":true}");
        assertEquals("{\"valid\":true}", resolved.encode(event()));
    }

    private static LogyardConfig config() {
        String text = """
                schema = 1
                [service]
                name = "test-service"
                environment = "test"
                [delivery]
                mode = "sync"
                [formatters.line]
                type = "template"
                template = "{level} {message}"
                [json_profiles.application]
                preset = "compact"
                [encoders.record]
                type = "json"
                profile = "application"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        return LogyardConfigLoader.parse(text, "output-resolver.toml", Path.of("."), Map.of());
    }

    private static LogEvent event() {
        return new LogEvent(
                1_700_000_000_000L,
                1_700_000_000_000_000_000L,
                Level.INFO,
                "com.acme.Worker",
                null,
                "hello",
                null,
                AttributeSet.EMPTY,
                null,
                7L,
                "worker");
    }
}
