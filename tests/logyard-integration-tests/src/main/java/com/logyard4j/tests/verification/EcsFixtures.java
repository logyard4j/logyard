package com.logyard4j.tests.verification;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.encoding.JsonAttributeTransform;
import com.logyard4j.output.json.encoding.JsonEncoder;
import com.logyard4j.output.json.encoding.JsonProfile;
import com.logyard4j.output.json.encoding.ResourceAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Produces actual encoder output for the Elasticsearch ingestion gate, without a repair pipeline. */
public final class EcsFixtures {
    private EcsFixtures() {
    }

    public static void write(Path directory) throws IOException {
        Files.createDirectories(directory);
        ResourceAttributes resource = ResourceAttributes.service("logyard-canary", "tests", "test",
                "canary-version", "node-1", Map.of("region", "local"));
        AttributeSet attributes = AttributeSet.builder().put("count", 7).put("paid", true)
                .put("nested", Map.of("secret", "[REDACTED]"))
                .put("tags", List.of("one", "two"))
                .put("trace_id", "0123456789abcdef0123456789abcdef")
                .put("span_id", "0123456789abcdef").put("trace_flags", "01").build();
        JsonEncoder encoder = new JsonEncoder(resource, JsonProfile.named("ecs"));
        Files.writeString(directory.resolve("base.json"), encoder.encode(event(attributes, null)));
        IllegalStateException failure = new IllegalStateException("failed", new RuntimeException("cause"));
        failure.addSuppressed(new IllegalArgumentException("suppressed"));
        Files.writeString(directory.resolve("exception.json"), encoder.encode(event(attributes, failure)));

        JsonProfile excluded = JsonProfile.custom("private", "ecs", Map.of(),
                List.of("body", "message_template", "logger", "attributes", "resource", "exception"),
                JsonAttributeTransform.nested());
        Files.writeString(directory.resolve("excluded.json"), new JsonEncoder(resource, excluded).encode(event(attributes, failure)));

        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        JsonEncoder large = new JsonEncoder(new ResourceAttributes(Map.of("noisy", controls)), JsonProfile.named("ecs"));
        Throwable oversized = new IllegalStateException("\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS));
        Files.writeString(directory.resolve("truncated.json"), large.encode(event(AttributeSet.of("payload", controls), oversized)));
    }

    private static LogEvent event(AttributeSet attributes, Throwable failure) {
        long millis = Instant.parse("2026-09-11T00:00:00Z").toEpochMilli();
        return new LogEvent(millis, millis * 1_000_000, Level.INFO, "orders.Service", "fixture.record",
                "accepted {}", new Object[] {7}, attributes, failure, 7, "worker");
    }
}
