package com.zsumz.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.output.json.file.lease.FileLease;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RotatingFileWriterFailureTest {
    private static final EventEncoder ENCODER = ignored -> "{\"event\":\"test\"}";

    @Test
    void archiveStartupFailurePreservesDataAndLeavesATerminalCoherentWriter() throws Exception {
        Path directory = Files.createTempDirectory("logyard-archive-startup-failure-");
        Path output = directory.resolve("events.jsonl");
        Path archive = directory.resolve("events.20260721T000000.000Z.000000.jsonl");
        Files.writeString(output, "KEEP-ME\n", StandardCharsets.UTF_8);
        Files.writeString(archive, "archive\n", StandardCharsets.UTF_8);
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".gz"), "occupied", StandardCharsets.UTF_8);
        String workerName = "logyard-archive-maintenance-" + output.getFileName();
        JsonFileSink sink = JsonFileSink.prepare(
                output,
                ENCODER,
                1_024,
                Duration.ofMinutes(1),
                false,
                rotation(RotationPolicy.Compression.GZIP));
        sink.activate();

        IllegalStateException first = assertThrows(IllegalStateException.class, () -> sink.accept(event()));

        assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));
        assertFalse(threadAlive(workerName));
        ComponentHealth health = sink.health("json");
        assertEquals(HealthStatus.FAILED, health.status());
        assertEquals("failed", health.details().get("writer_state"));
        assertNotNull(health.details().get("writer_failure"));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }

        IllegalStateException second = assertThrows(IllegalStateException.class, () -> sink.accept(event()));
        assertEquals(first, second.getCause());
        sink.close();
        assertFalse(threadAlive(workerName));
    }

    @Test
    void replacementOpenFailureRecoversOnTheFollowingRecord() throws Exception {
        Path directory = Files.createTempDirectory("logyard-rotation-open-failure-");
        Path output = directory.resolve("events.jsonl");
        AtomicInteger opens = new AtomicInteger();
        DataFileOpener opener = (path, bufferBytes, append) -> {
            int attempt = opens.incrementAndGet();
            if (attempt == 2) {
                throw new UncheckedIOException("injected replacement failure", new IOException("open failed"));
            }
            return BufferedFileWriter.open(path, bufferBytes, append);
        };
        RotatingFileWriter writer = new RotatingFileWriter(
                output,
                1_024,
                false,
                rotation(RotationPolicy.Compression.NONE),
                opener);

        try {
            writer.initializeForDirectUse();
            writer.writeRecord("x".repeat(1_023).getBytes(StandardCharsets.UTF_8), (byte) '\n');

            assertThrows(UncheckedIOException.class, () ->
                    writer.writeRecord("ROTATE".getBytes(StandardCharsets.UTF_8), (byte) '\n'));
            assertEquals(2, opens.get());
            assertNotNull(writer.healthSnapshot().writerFailureType());

            writer.writeRecord("RECOVERED".getBytes(StandardCharsets.UTF_8), (byte) '\n');
            writer.flush();
            assertEquals(3, opens.get());
            assertNull(writer.healthSnapshot().writerFailureType());
        } finally {
            writer.close();
        }

        assertEquals("RECOVERED\n", Files.readString(output, StandardCharsets.UTF_8));
        List<Path> archives;
        try (var files = Files.list(directory)) {
            archives = files.filter(path -> path.getFileName().toString()
                            .matches("events\\.\\d{8}T\\d{6}\\.\\d{3}Z\\.\\d{6}\\.jsonl"))
                    .toList();
        }
        assertEquals(1, archives.size());
        assertEquals("x".repeat(1_023) + "\n", Files.readString(archives.get(0), StandardCharsets.UTF_8));
    }

    private static RotationPolicy rotation(RotationPolicy.Compression compression) {
        return new RotationPolicy(1_024, 10, compression, Duration.ofSeconds(2));
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private static LogEvent event() {
        return new LogEvent(
                0,
                0,
                Level.INFO,
                "test.Logger",
                "test.event",
                "event",
                new Object[0],
                AttributeSet.EMPTY,
                null,
                1,
                "test");
    }
}
