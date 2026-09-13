package com.logyard4j.logyard.tests.verification;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.loading.LogyardConfigLoader;

import java.nio.file.Path;
import java.util.Map;

final class VerificationFixtures {
    private VerificationFixtures() {
    }

    static LogyardConfig extensionConfig(String filterConfig, String outputConfig, int capacity) {
        return parse(extensionText(
                "test-output",
                "com.logyard4j.logyard.tests.extensions.TestOutputProvider",
                outputConfig,
                "test-filter",
                filterConfig,
                capacity));
    }

    static String extensionText(
            String outputProvider,
            String outputImplementation,
            String outputConfig,
            String filterProvider,
            String filterConfig,
            int capacity) {
        return """
                schema = 1
                [service]
                name = "tests"
                [runtime]
                shutdown_timeout = "2s"
                [delivery]
                mode = "async"
                capacity = %d
                [formatters.line]
                type = "custom"
                provider = "test-formatter"
                implementation = "com.logyard4j.logyard.tests.extensions.TestFormatterProvider"
                [formatters.line.config]
                prefix = "fmt:"
                [encoders.record]
                type = "custom"
                provider = "test-encoder"
                implementation = "com.logyard4j.logyard.tests.extensions.TestEncoderProvider"
                [encoders.record.config]
                tag = "enc"
                [enrichers.add]
                provider = "test-enricher"
                implementation = "com.logyard4j.logyard.tests.extensions.TestEnricherProvider"
                [enrichers.add.config]
                attribute = "verified"
                [filters.allow]
                type = "custom"
                provider = "%s"
                [filters.allow.config]
                %s
                [loggers]
                root = { outputs = ["capture"], filters = ["allow"], enrich = ["add"] }
                [outputs.capture]
                type = "custom"
                provider = "%s"
                implementation = "%s"
                formatter = "line"
                encoder = "record"
                [outputs.capture.config]
                %s
                """.formatted(capacity, filterProvider, filterConfig, outputProvider, outputImplementation, outputConfig);
    }

    static LogyardConfig parse(String text) {
        return LogyardConfigLoader.parse(text, "core-verification.toml", Path.of("."), Map.of());
    }

    static LogEvent event(Level level, String logger, String eventName, String template, Object[] arguments, AttributeSet attributes) {
        Thread thread = Thread.currentThread();
        return new LogEvent(
                1_721_520_000_000L,
                1_721_520_000_000_000_000L,
                level,
                logger,
                eventName,
                template,
                arguments,
                attributes,
                null,
                thread.threadId(),
                thread.getName());
    }
}
