package com.zsumz.logyard.tests;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.format.TextTemplate;
import com.zsumz.logyard.api.spi.EventEncoder;
import com.zsumz.logyard.api.spi.ProviderConfiguration;
import com.zsumz.logyard.api.spi.ProviderConfigurationSpec;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.config.ConfigurationException;
import com.zsumz.logyard.config.JsonAttributeTransformConfig;
import com.zsumz.logyard.config.JsonProfileConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.LogyardConfigLoader;
import com.zsumz.logyard.core.processing.RateLimitProcessor;
import com.zsumz.logyard.core.processing.SamplingProcessor;
import com.zsumz.logyard.output.console.TemplateTextFormatter;
import com.zsumz.logyard.output.json.encoding.JsonAttributeTransform;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.JsonProfile;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.zsumz.logyard.runtime.extension.ExtensionGuardrails;
import com.zsumz.logyard.tests.extensions.TestOutputProvider;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dependency-free integration checks for Logyard's core logging path. */
public final class CoreVerificationMain {
    private static final int EXPECTED_CHECKS = 6;
    private int passed;

    private CoreVerificationMain() {
    }

    public static void main(String[] arguments) throws Exception {
        CoreVerificationMain verification = new CoreVerificationMain();
        int passed = verification.run();
        if (passed != EXPECTED_CHECKS) {
            throw new AssertionError("expected " + EXPECTED_CHECKS + " checks but ran " + passed);
        }
        System.out.println("Core verification passed: " + passed + "/" + EXPECTED_CHECKS);
    }

    private int run() throws Exception {
        check("validated text templates and JSON transforms", this::templatesAndProfiles);
        check("bounded sampling and rate limiting", this::samplingAndRateLimiting);
        check("custom formatter, encoder, processor, and output SPIs", this::extensionSpisAndOutput);
        check("strict provider configuration and extension guardrails", this::strictConfiguration);
        check("independent filter and enricher inheritance", this::processorInheritance);
        check("bounded asynchronous logging delivery", this::boundedAsyncLogging);
        return passed;
    }

    private void templatesAndProfiles() {
        TextTemplate template = TextTemplate.compile("[{level}] {message} {fields}");
        String formatted = new TemplateTextFormatter(template, ZoneOffset.UTC).format(event(
                Level.INFO,
                "tests.Template",
                "template.event",
                "hello {}",
                new Object[] {"world"},
                AttributeSet.of("tenant", "acme")));
        require(formatted.contains("[INFO] hello world tenant=acme"),
                "validated formatter did not render expected fields");
        expect(IllegalArgumentException.class, () -> TextTemplate.compile("{unknown} {message}"));
        expect(IllegalArgumentException.class, () -> TextTemplate.compile("{level}"));

        LogyardConfig config = parse("""
                schema = 1
                [json_profiles.application]
                preset = "ecs"
                rename = { body = "log.message" }
                drop = ["message_template"]
                [json_profiles.application.attributes]
                mode = "flatten"
                prefix = "app."
                include = ["tenant", "secret"]
                exclude = ["secret"]
                rename = { tenant = "tenant_id" }
                [encoders.application]
                type = "json"
                profile = "application"
                [loggers]
                root = { outputs = ["json"] }
                [outputs.json]
                type = "stream"
                encoder = "application"
                """);
        JsonProfileConfig configured = config.jsonProfiles().get("application");
        JsonAttributeTransformConfig attributes = configured.attributes();
        JsonProfile profile = JsonProfile.custom(
                configured.name(),
                configured.preset(),
                configured.rename(),
                configured.drop(),
                new JsonAttributeTransform(
                        JsonAttributeTransform.Mode.parse(attributes.mode()),
                        attributes.prefix(),
                        attributes.include(),
                        attributes.exclude(),
                        attributes.rename()));
        String encoded = new JsonEncoder(
                ResourceAttributes.service("tests", "test", "1"),
                profile).encode(event(
                        Level.INFO,
                        "tests.Json",
                        "profile.event",
                        "profile {}",
                        new Object[] {"ok"},
                        AttributeSet.builder()
                                .put("tenant", "acme")
                                .put("secret", "hidden")
                                .put("ignored", true)
                                .build()));
        require(encoded.contains("\"@timestamp\""), "ECS timestamp mapping is missing");
        require(encoded.contains("\"log.message\":\"profile ok\""),
                "custom body mapping is missing");
        require(encoded.contains("\"app.tenant_id\":\"acme\""),
                "flattened attribute rename is missing");
        require(!encoded.contains("hidden") && !encoded.contains("ignored"),
                "attribute include/exclude transform leaked fields");

        LogyardConfig collision = parse("""
                schema = 1
                [json_profiles.bad]
                preset = "logyard"
                rename = { body = "logger" }
                [encoders.bad]
                type = "json"
                profile = "bad"
                [outputs.json]
                type = "stream"
                encoder = "bad"
                """);
        expect(IllegalArgumentException.class, () -> LogyardRuntimeFactory.validate(collision));
    }

    private void samplingAndRateLimiting() {
        LogEvent info = event(Level.INFO, "tests.Sample", "sample", "hello", null,
                AttributeSet.EMPTY);
        LogEvent warn = event(Level.WARN, "tests.Sample", "sample", "warn", null,
                AttributeSet.EMPTY);
        SamplingProcessor dropAll = new SamplingProcessor(0.0d, "logger", 17L);
        equal(null, dropAll.process(info));
        require(dropAll.process(warn) == warn, "WARN must bypass sampling");

        SamplingProcessor deterministic = new SamplingProcessor(0.5d, "event", 99L);
        boolean first = deterministic.process(info) != null;
        boolean second = deterministic.process(info) != null;
        equal(first, second);

        RateLimitProcessor limiter = new RateLimitProcessor(0.001d, 1, "logger", 2);
        require(limiter.process(info) == info, "first rate-limit token should pass");
        equal(null, limiter.process(info));
        limiter.process(event(Level.INFO, "tests.Other", "sample", "two", null,
                AttributeSet.EMPTY));
        limiter.process(event(Level.INFO, "tests.Third", "sample", "three", null,
                AttributeSet.EMPTY));
        require(limiter.trackedKeys() <= 2, "rate limiter exceeded key bound");
        require(limiter.process(warn) == warn, "WARN must bypass rate limiting");
    }

    private void extensionSpisAndOutput() {
        TestOutputProvider.reset();
        LogyardConfig config = extensionConfig("allow = true", "marker = \"batch8-core\"", 16);
        LogyardRuntimeFactory.validate(config);
        String callerThread = Thread.currentThread().getName();
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            EffectiveRoute route = runtime.explain("tests.Custom");
            equal(List.of("allow", "add"), route.processors());
            runtime.logger("tests.Custom").atInfo().log("hello");
            runtime.flush();
            ComponentHealth output = runtime.health().components().stream()
                    .filter(component -> "capture".equals(component.name()))
                    .findFirst()
                    .orElseThrow();
            equal("async", output.details().get("delivery"));
            equal("false", output.details().get("caller_thread_delivery"));
        }
        TestOutputProvider.Snapshot snapshot = TestOutputProvider.snapshot();
        equal("batch8-core", snapshot.marker());
        equal("capture", snapshot.outputName());
        equal("tests", snapshot.serviceName());
        equal("fmt:\\nhello", snapshot.formatted());
        equal("enc:hello", snapshot.encoded());
        equal(true, snapshot.enriched());
        require(!callerThread.equals(snapshot.threadName()),
                "custom output executed on the logging thread");
    }

    private void strictConfiguration() {
        LogyardConfig unknownKey = extensionConfig(
                "allow = true", "marker = \"batch8-core\"\nextra = 1", 16);
        expect(IllegalArgumentException.class, () -> LogyardRuntimeFactory.validate(unknownKey));

        LogyardConfig wrongPin = parse(extensionText(
                "test-output",
                "com.example.NotTheProvider",
                "marker = \"batch8-core\"",
                "test-filter",
                "allow = true",
                16));
        expect(IllegalArgumentException.class, () -> LogyardRuntimeFactory.validate(wrongPin));

        LogyardConfig wrongKind = parse(extensionText(
                "test-output",
                "com.zsumz.logyard.tests.extensions.TestOutputProvider",
                "marker = \"batch8-core\"",
                "test-enricher",
                "attribute = \"not-a-filter\"",
                16));
        expect(IllegalArgumentException.class, () -> LogyardRuntimeFactory.validate(wrongKind));

        expect(ConfigurationException.class, () -> parse("""
                schema = 1
                [delivery]
                mode = "sync"
                [outputs.capture]
                type = "custom"
                provider = "test-output"
                [outputs.capture.config]
                marker = "batch8-core"
                """));

        EventEncoder lineBreaking = ExtensionGuardrails.encoder(event -> "bad\nrecord");
        expect(IllegalArgumentException.class, () -> lineBreaking.encode(event(
                Level.INFO, "tests.Guardrail", null, "message", null, AttributeSet.EMPTY)));
        TextFormatter oversized = ExtensionGuardrails.formatter(
                event -> "x".repeat(CaptureLimits.MAX_TEXT_CHARS + 100));
        equal(CaptureLimits.MAX_TEXT_CHARS, oversized.format(event(
                Level.INFO, "tests.Guardrail", null, "message", null,
                AttributeSet.EMPTY)).length());

        ProviderConfiguration secret = new ProviderConfiguration(Map.of(
                "marker", "provider-secret", "retries", 3L));
        require(!secret.toString().contains("provider-secret"),
                "provider configuration string exposed a value");
        ProviderConfigurationSpec normalized = ProviderConfigurationSpec.of(
                Set.of(" marker "), Set.of("marker"));
        normalized.validate(new ProviderConfiguration(Map.of("marker", "value")));
        equal(Set.of("marker"), normalized.allowedKeys());
        expect(IllegalArgumentException.class, () -> ProviderConfigurationSpec.of(
                Set.of(" marker", "marker"), Set.of()));
        expect(IllegalArgumentException.class, () -> new ProviderConfiguration(
                Map.of("ratio", Double.NaN)));
    }

    private void processorInheritance() {
        LogyardConfig config = parse("""
                schema = 1
                [filters.sample]
                type = "sampling"
                probability = 1.0
                key = "logger"
                [enrichers.add]
                provider = "test-enricher"
                [enrichers.add.config]
                attribute = "verified"
                [loggers]
                root = { outputs = ["console"], filters = ["sample"] }
                "tests" = { enrich = ["add"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """);
        EffectiveRoute route = LogyardRuntimeFactory.explain(config, "tests.Child");
        equal(List.of("sample", "add"), route.processors());
    }

    private void boundedAsyncLogging() {
        int events = 2_000;
        TestOutputProvider.reset();
        LogyardConfig config = extensionConfig(
                "allow = true", "marker = \"smoke\"", 4_096);
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            for (int index = 0; index < events; index++) {
                runtime.logger("tests.Smoke").atInfo().add("index", index).log("event {}", index);
            }
            runtime.flush();
        }
        equal((long) events, TestOutputProvider.deliveredCount());
        equal((long) events, TestOutputProvider.snapshot().deliveredCount());
    }

    private static LogyardConfig extensionConfig(
            String filterConfig,
            String outputConfig,
            int capacity) {
        return parse(extensionText(
                "test-output",
                "com.zsumz.logyard.tests.extensions.TestOutputProvider",
                outputConfig,
                "test-filter",
                filterConfig,
                capacity));
    }

    private static String extensionText(
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
                implementation = "com.zsumz.logyard.tests.extensions.TestFormatterProvider"
                [formatters.line.config]
                prefix = "fmt:"
                [encoders.record]
                type = "custom"
                provider = "test-encoder"
                implementation = "com.zsumz.logyard.tests.extensions.TestEncoderProvider"
                [encoders.record.config]
                tag = "enc"
                [enrichers.add]
                provider = "test-enricher"
                implementation = "com.zsumz.logyard.tests.extensions.TestEnricherProvider"
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
                """.formatted(
                capacity,
                filterProvider,
                filterConfig,
                outputProvider,
                outputImplementation,
                outputConfig);
    }

    private static LogyardConfig parse(String text) {
        return LogyardConfigLoader.parse(text, "core-verification.toml", Path.of("."), Map.of());
    }

    private static LogEvent event(
            Level level,
            String logger,
            String eventName,
            String template,
            Object[] arguments,
            AttributeSet attributes) {
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

    private void check(String name, CheckedRunnable action) throws Exception {
        action.run();
        passed++;
        System.out.println("PASS  " + name);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.deepEquals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> expected, CheckedRunnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError(
                    "expected " + expected.getName() + " but got " + failure, failure);
        }
        throw new AssertionError(
                "expected " + expected.getName() + " but no exception was thrown");
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
