package com.zsumz.logyard.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.output.console.style.ConsoleTheme;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;

import java.nio.file.Path;
import java.util.Map;
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
