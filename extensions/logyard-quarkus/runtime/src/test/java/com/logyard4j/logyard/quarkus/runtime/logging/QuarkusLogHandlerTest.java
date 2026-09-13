package com.logyard4j.logyard.quarkus.runtime.logging;

import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeOwner;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuarkusLogHandlerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsCompleteJbossRecordAndDoesNotOwnSharedRuntime() throws Exception {
        Path output = temporaryDirectory.resolve("events.jsonl");
        LogyardConfigurationSource source = LogyardConfigurationSource.text(
                "Quarkus handler test",
                configuration(output),
                temporaryDirectory);

        try (RuntimeBundle bundle = LogyardBootstrap.acquire(RuntimeOwner.APPLICATION, source)) {
            QuarkusLogHandler handler = new QuarkusLogHandler(bundle.runtime());
            ExtLogRecord record = record();

            handler.publish(record);
            handler.flush();
            handler.close();
            handler.close();
            handler.publish(record);
            bundle.runtime().logger("native.after.handler").info("runtime remains active");
            bundle.runtime().flush();
        }

        String events = Files.readString(output);
        assertEquals(1, occurrences(events, "\"body\":\"Quarkus mapped event alpha\""));
        assertTrue(events.contains("\"logger\":\"example.quarkus\""));
        assertTrue(events.contains("\"severity_text\":\"WARN\""));
        assertTrue(events.contains("\"quarkus.message_template\":\"Quarkus mapped event %s\""));
        assertTrue(events.contains("\"request.id\":\"request-7\""), events);
        assertTrue(events.contains("\"authorization\":\"[REDACTED]\""), events);
        assertTrue(events.contains("\"session.token\":\"[REDACTED]\""), events);
        assertFalse(events.contains("\"quarkus.mdc\""));
        assertFalse(events.contains("Bearer private-token"));
        assertFalse(events.contains("session-secret"));
        assertTrue(events.contains("\"quarkus.ndc\":\"operation\""));
        assertTrue(events.contains("\"code.namespace\":\"example.Source\""));
        assertTrue(events.contains("\"thread\":{\"id\":91,\"name\":\"quarkus-worker\"}"));
        assertTrue(events.contains("\"message\":\"expected Quarkus failure\""));
        assertTrue(events.contains("\"body\":\"runtime remains active\""));
        assertFalse(events.contains("\"body\":\"Quarkus mapped event %s\""));
    }

    private static ExtLogRecord record() {
        ExtLogRecord record = new ExtLogRecord(
                java.util.logging.Level.WARNING,
                "Quarkus mapped event %s",
                ExtLogRecord.FormatStyle.PRINTF,
                QuarkusLogHandlerTest.class.getName());
        record.setLoggerName("example.quarkus");
        record.setParameters(new Object[]{"alpha"});
        record.setThrown(new IllegalStateException("expected Quarkus failure"));
        record.setInstant(Instant.parse("2026-07-23T12:34:56.789Z"));
        record.setLongThreadID(91L);
        record.setThreadName("quarkus-worker");
        record.setSourceClassName("example.Source");
        record.setSourceMethodName("handle");
        record.setSourceFileName("Source.java");
        record.setSourceLineNumber(42);
        record.setSourceModuleName("example.module");
        record.setSourceModuleVersion("1.0");
        record.setHostName("test-host");
        record.setProcessName("test-process");
        record.setProcessId(73L);
        record.putMdc("request.id", "request-7");
        record.putMdc("authorization", "Bearer private-token");
        record.putMdc("session.token", "session-secret");
        record.setNdc("operation");
        return record;
    }

    private static String configuration(Path output) {
        return """
                schema = 1

                [runtime]
                watch = false
                internal_status = "off"

                [delivery]
                mode = "sync"
                capacity = 16

                [context]
                mdc = ["request.id", "authorization", "session.token"]
                redact = ["authorization", "*.token"]

                [loggers]
                root = { level = "trace", outputs = ["json"] }

                [outputs.json]
                type = "file"
                path = "%s"
                append = false
                buffer = "4KiB"
                flush = "0s"
                """.formatted(output.toString().replace("\\", "\\\\"));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
