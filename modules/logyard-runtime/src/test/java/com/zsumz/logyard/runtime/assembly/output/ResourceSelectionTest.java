package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.config.runtime.ResourceConfig;
import com.zsumz.logyard.output.json.encoding.JsonAttributeTransform;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.JsonProfile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceSelectionTest {
    @Test
    void filtersBeforeResourceCaptureCanConsumeTheRetainedBudget() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) values.put("noisy" + index, "x".repeat(4_096));
        values.put("kept", "retained-value");
        var resource = EncoderResolver.resource(config(new ResourceConfig(values, List.of("kept"), List.of())));
        assertEquals(Map.of("kept", "retained-value"), resource.values());
    }

    @Test
    void exclusionsHoldAcrossProfilesExceptionsAndEscapingFallback() {
        for (String preset : List.of("logyard", "ecs", "compact")) {
            for (boolean custom : new boolean[] {false, true}) {
                JsonProfile profile = custom ? JsonProfile.custom("custom", preset,
                        Map.of("timestamp", "time"), List.of(), JsonAttributeTransform.nested()) : JsonProfile.named(preset);
                for (boolean large : new boolean[] {false, true}) {
                    Map<String, String> values = new LinkedHashMap<>();
                    values.put("region", "private-region");
                    if (large) {
                        for (int index = 0; index < 6; index++) values.put("noisy" + index, "\u0000".repeat(4_096));
                    }
                    var resource = EncoderResolver.resource(config(new ResourceConfig(values, List.of(),
                            List.of("service.name", "service.instance.id", "region"))));
                    String json = new JsonEncoder(resource, profile).encode(event(large));
                    assertFalse(json.contains("private-service"));
                    assertFalse(json.contains("private-region"));
                    assertFalse(json.contains("private-instance"));
                    if (large) assertTrue(json.contains("\"logyard.output.truncated\":true"));
                    else assertTrue(json.contains("visible-version"));
                }
            }
        }
    }

    private static LogEvent event(boolean large) {
        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        Throwable failure = new IllegalStateException(large
                ? "\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS) : "visible failure");
        return new LogEvent(0, 1, Level.ERROR, "example", "operation", "visible message", null,
                large ? AttributeSet.of("payload", controls) : AttributeSet.EMPTY, failure, 1, "main");
    }

    private static LogyardConfig config(ResourceConfig resource) {
        LogyardConfig base = LogyardConfigLoader.parse("""
                schema = 1
                [service]
                name = "private-service"
                version = "visible-version"
                instance_id = "private-instance"
                [outputs.console]
                type = "console"
                """, "resource.toml", Path.of("."), Map.of());
        return new LogyardConfig(base.schema(), base.service(), resource, base.runtime(), base.context(),
                base.rootLogger(), base.loggers(), base.delivery(), base.outputs(), base.themes(), base.formatters(),
                base.encoders(), base.jsonProfiles(), base.enrichers(), base.filters());
    }
}
