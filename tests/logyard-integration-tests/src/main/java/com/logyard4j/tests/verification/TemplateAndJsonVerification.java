package com.logyard4j.tests.verification;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.format.TextTemplate;
import com.logyard4j.config.encoding.JsonAttributeTransformConfig;
import com.logyard4j.config.encoding.JsonProfileConfig;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.output.console.rendering.TemplateTextFormatter;
import com.logyard4j.output.json.encoding.JsonAttributeTransform;
import com.logyard4j.output.json.encoding.JsonEncoder;
import com.logyard4j.output.json.encoding.JsonProfile;
import com.logyard4j.output.json.encoding.ResourceAttributes;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;

import java.time.ZoneOffset;

import static com.logyard4j.tests.verification.VerificationAssertions.expect;
import static com.logyard4j.tests.verification.VerificationAssertions.require;
import static com.logyard4j.tests.verification.VerificationFixtures.event;
import static com.logyard4j.tests.verification.VerificationFixtures.parse;

final class TemplateAndJsonVerification implements VerificationCase {
    @Override
    public String description() {
        return "validated text templates and JSON transforms";
    }

    @Override
    public void verify() {
        verifyTextTemplate();
        verifyJsonProfile();
        verifyProfileCollisions();
    }

    private static void verifyTextTemplate() {
        TextTemplate template = TextTemplate.compile("[{level}] {message} {fields}");
        String formatted = new TemplateTextFormatter(template, ZoneOffset.UTC).format(event(
                Level.INFO,
                "tests.Template",
                "template.event",
                "hello {}",
                new Object[] {"world"},
                AttributeSet.of("tenant", "acme")));
        require(formatted.contains("[INFO] hello world tenant=acme"), "validated formatter did not render expected fields");
        expect(IllegalArgumentException.class, () -> TextTemplate.compile("{unknown} {message}"));
        require("INFO approved".equals(TextTemplate.compile("{level} approved").render(name -> "INFO")),
                "templates must preserve deliberate message omission");
    }

    private static void verifyJsonProfile() {
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
        String encoded = new JsonEncoder(ResourceAttributes.service("tests", "test", "1"), profile).encode(event(
                Level.INFO,
                "tests.Json",
                "profile.event",
                "profile {}",
                new Object[] {"ok"},
                AttributeSet.builder().put("tenant", "acme").put("secret", "hidden").put("ignored", true).build()));
        require(encoded.contains("\"@timestamp\""), "ECS timestamp mapping is missing");
        require(encoded.contains("\"log.message\":\"profile ok\""), "custom body mapping is missing");
        require(encoded.contains("\"app.tenant_id\":\"acme\""), "flattened attribute rename is missing");
        require(!encoded.contains("hidden") && !encoded.contains("ignored"), "attribute include/exclude transform leaked fields");
    }

    private static void verifyProfileCollisions() {
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
}
