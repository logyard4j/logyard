package com.logyard4j.logyard.tests.verification;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;
import com.logyard4j.logyard.config.ConfigurationException;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.extension.ExtensionGuardrails;

import java.util.Map;
import java.util.Set;

import static com.logyard4j.logyard.tests.verification.VerificationAssertions.equal;
import static com.logyard4j.logyard.tests.verification.VerificationAssertions.expect;
import static com.logyard4j.logyard.tests.verification.VerificationAssertions.require;
import static com.logyard4j.logyard.tests.verification.VerificationFixtures.event;
import static com.logyard4j.logyard.tests.verification.VerificationFixtures.extensionConfig;
import static com.logyard4j.logyard.tests.verification.VerificationFixtures.extensionText;
import static com.logyard4j.logyard.tests.verification.VerificationFixtures.parse;

final class ExtensionGuardrailVerification implements VerificationCase {
    @Override
    public String description() {
        return "strict provider configuration and extension guardrails";
    }

    @Override
    public void verify() {
        verifyExtensionIdentityAndConfiguration();
        verifyValueGuardrails();
        verifyProviderConfiguration();
    }

    private static void verifyExtensionIdentityAndConfiguration() {
        LogyardConfig unknownKey = extensionConfig("allow = true", "marker = \"batch8-core\"\nextra = 1", 16);
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
                "com.logyard4j.logyard.tests.extensions.TestOutputProvider",
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
    }

    private static void verifyValueGuardrails() {
        EventEncoder lineBreaking = ExtensionGuardrails.encoder(logEvent -> "bad\nrecord");
        expect(IllegalArgumentException.class, () -> lineBreaking.encode(
                event(Level.INFO, "tests.Guardrail", null, "message", null, AttributeSet.EMPTY)));
        TextFormatter oversized = ExtensionGuardrails.formatter(logEvent -> "x".repeat(CaptureLimits.MAX_TEXT_CHARS + 100));
        equal(CaptureLimits.MAX_TEXT_CHARS, oversized.format(
                event(Level.INFO, "tests.Guardrail", null, "message", null, AttributeSet.EMPTY)).length());
    }

    private static void verifyProviderConfiguration() {
        ProviderConfiguration secret = new ProviderConfiguration(Map.of("marker", "provider-secret", "retries", 3L));
        require(!secret.toString().contains("provider-secret"), "provider configuration string exposed a value");
        ProviderConfigurationSpec normalized = ProviderConfigurationSpec.of(Set.of(" marker "), Set.of("marker"));
        normalized.validate(new ProviderConfiguration(Map.of("marker", "value")));
        equal(Set.of("marker"), normalized.allowedKeys());
        expect(IllegalArgumentException.class, () -> ProviderConfigurationSpec.of(Set.of(" marker", "marker"), Set.of()));
        expect(IllegalArgumentException.class, () -> new ProviderConfiguration(Map.of("ratio", Double.NaN)));
    }
}
