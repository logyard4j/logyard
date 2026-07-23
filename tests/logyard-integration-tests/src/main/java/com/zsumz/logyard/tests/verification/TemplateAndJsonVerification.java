package com.zsumz.logyard.tests.verification;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.format.TextTemplate;
import com.zsumz.logyard.config.encoding.JsonAttributeTransformConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.output.console.TemplateTextFormatter;
import com.zsumz.logyard.output.json.encoding.JsonAttributeTransform;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.JsonProfile;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.runtime.assembly.LogyardRuntimeFactory;

import java.time.ZoneOffset;

import static com.zsumz.logyard.tests.verification.VerificationAssertions.expect;
import static com.zsumz.logyard.tests.verification.VerificationAssertions.require;
import static com.zsumz.logyard.tests.verification.VerificationFixtures.event;
import static com.zsumz.logyard.tests.verification.VerificationFixtures.parse;

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
        expect(IllegalArgumentException.class, () -> TextTemplate.compile("{level}"));
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
