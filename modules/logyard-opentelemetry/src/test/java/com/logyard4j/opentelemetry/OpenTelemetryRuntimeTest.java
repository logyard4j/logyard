package com.logyard4j.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class OpenTelemetryRuntimeTest {
    @Test
    void capturesBeforeAsyncDeliveryAndRequiresExecutorPropagationForEveryProfile() throws Exception {
        for (String profile : List.of("logyard", "ecs", "compact")) {
            Path directory = Files.createTempDirectory("logyard-trace-");
            Path output = directory.resolve("events.jsonl");
            try {
                try (RuntimeBundle application = LogyardBootstrap.start(configuration(output, profile));
                        var executor = Executors.newSingleThreadExecutor()) {
                    var logger = application.runtime().logger("trace.example");
                    Context context = OpenTelemetryContextProviderTest.context(true);
                    try (Scope ignored = context.makeCurrent()) {
                        logger.info("direct");
                        executor.submit(context.wrap(() -> logger.info("wrapped"))).get(5, TimeUnit.SECONDS);
                        executor.submit(() -> logger.info("unwrapped")).get(5, TimeUnit.SECONDS);
                    }
                    logger.info("outside");
                }
                List<String> records = Files.readAllLines(output);
                assertEquals(4, records.size());
                String traceKey = profile.equals("ecs") ? "trace.id" : "trace_id";
                String spanKey = profile.equals("ecs") ? "span.id" : "span_id";
                for (int index = 0; index < 2; index++) {
                    assertTrue(records.get(index).contains('"' + traceKey + "\":\"" + OpenTelemetryContextProviderTest.TRACE + '"'));
                    assertTrue(records.get(index).contains('"' + spanKey + "\":\"" + OpenTelemetryContextProviderTest.SPAN + '"'));
                    assertTrue(records.get(index).contains("\"baggage.tenant.id\":\"tenant-7\""));
                }
                for (int index = 2; index < 4; index++) {
                    assertFalse(records.get(index).contains(OpenTelemetryContextProviderTest.TRACE));
                    assertFalse(records.get(index).contains("tenant-7"));
                }
                assertFalse(String.join("", records).contains("private-value"));
            } finally {
                Files.deleteIfExists(output);
                Files.deleteIfExists(directory.resolve("events.jsonl.logyard.lock"));
                Files.deleteIfExists(directory);
            }
        }
    }

    private static LogyardConfigurationSource configuration(Path output, String profile) {
        return LogyardConfigurationSource.text("trace context test", """
                schema = 1
                [runtime]
                watch = false
                internal_status = "off"
                [delivery]
                mode = "async"
                capacity = 16
                [context]
                trace = true
                baggage = ["tenant.id"]
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [encoders.application]
                type = "json"
                profile = "%s"
                [outputs.json]
                type = "file"
                path = "%s"
                encoder = "application"
                append = false
                flush = "0s"
                """.formatted(profile, output.toString().replace("\\", "\\\\")), output.getParent());
    }
}
