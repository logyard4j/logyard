package com.zsumz.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class JsonFileSinkTest {
    @Test
    void rotatesOnlyBetweenCompleteRecords() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-boundary-");
        Path output = directory.resolve("events.jsonl");
        writeRotating(output, 12, 10, RotationPolicy.Compression.NONE);
        for (Path file : ownedDataFiles(directory)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(content.isEmpty() || content.endsWith("\n"));
            assertTrue(content.lines().allMatch(line -> line.startsWith("{") && line.endsWith("}")));
        }
    }

    @Test
    void compressesCompletedArchives() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-gzip-");
        Path output = directory.resolve("events.jsonl");
        writeRotating(output, 12, 10, RotationPolicy.Compression.GZIP);
        List<Path> archives = gzipArchives(directory);
        assertFalse(archives.isEmpty());
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archives.get(0)))) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.endsWith("\n"));
        }
    }

    @Test
    void enforcesExactArchiveRetention() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-retention-");
        Path output = directory.resolve("events.jsonl");
        writeRotating(output, 30, 2, RotationPolicy.Compression.GZIP);
        assertEquals(2, gzipArchives(directory).size());
    }

    @Test
    void preservesLookalikeFiles() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-lookalike-");
        Path output = directory.resolve("events.jsonl");
        Path lookalike = directory.resolve("events.20260721T000000.000Z.000000.jsonl.gz.keep");
        Files.writeString(lookalike, "not Logyard-owned", StandardCharsets.UTF_8);
        writeRotating(output, 20, 1, RotationPolicy.Compression.GZIP);
        assertTrue(Files.exists(lookalike));
    }

    @Test
    void removesExactStaleCompressionTemporary() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-reconcile-");
        Path output = directory.resolve("events.jsonl");
        Path temporary = directory.resolve("events.20260721T000000.000Z.000000.jsonl.gz.tmp");
        Files.writeString(temporary, "partial", StandardCharsets.UTF_8);
        try (JsonFileSink openSink = sink(output, 10, RotationPolicy.Compression.GZIP)) {
            assertEquals(output.toAbsolutePath().normalize(), openSink.path());
            assertFalse(Files.exists(temporary));
        }
    }

    @Test
    void rejectsConcurrentOwnerInSameJvm() throws Exception {
        Path output = Files.createTempDirectory("logyard-json-lock-").resolve("events.jsonl");
        try (JsonFileSink first = plainSink(output)) {
            assertEquals(output.toAbsolutePath().normalize(), first.path());
            assertThrows(IllegalStateException.class, () -> plainSink(output));
        }
    }

    @Test
    void rejectsSymbolicLinkOutputWhenSupported() throws Exception {
        Path directory = Files.createTempDirectory("logyard-json-symlink-");
        Path target = directory.resolve("target.jsonl");
        Files.writeString(target, "target", StandardCharsets.UTF_8);
        Path symbolic = directory.resolve("events.jsonl");
        try {
            Files.createSymbolicLink(symbolic, target.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThrows(UncheckedIOException.class, () -> plainSink(symbolic));
    }

    @Test
    void releasesLeaseAfterClose() throws Exception {
        Path output = Files.createTempDirectory("logyard-json-reopen-").resolve("events.jsonl");
        try (JsonFileSink first = plainSink(output)) {
            first.accept(event(1));
        }
        assertDoesNotThrow(() -> {
            try (JsonFileSink second = plainSink(output)) {
                second.accept(event(2));
            }
        });
    }

    private static void writeRotating(
            Path output,
            int records,
            int keep,
            RotationPolicy.Compression compression) {
        try (JsonFileSink sink = sink(output, keep, compression)) {
            for (int index = 0; index < records; index++) {
                sink.accept(event(index));
            }
        }
    }

    private static JsonFileSink sink(
            Path output,
            int keep,
            RotationPolicy.Compression compression) {
        return new JsonFileSink(
                output,
                ResourceAttributes.service("test", "test", "1"),
                1_024,
                Duration.ZERO,
                false,
                new RotationPolicy(1_024, keep, compression, Duration.ofSeconds(3)));
    }

    private static JsonFileSink plainSink(Path output) {
        return new JsonFileSink(
                output,
                ResourceAttributes.service("test", "test", "1"),
                1_024,
                Duration.ZERO,
                true);
    }

    private static LogEvent event(int index) {
        return new LogEvent(
                0,
                0,
                Level.INFO,
                "test.Logger",
                "test.event",
                "record {} " + "x".repeat(320),
                new Object[] {index},
                AttributeSet.of("sequence", index),
                null,
                1,
                "test");
    }

    private static List<Path> gzipArchives(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".jsonl.gz"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> ownedDataFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("events.jsonl")
                                || name.matches("events\\.\\d{8}T\\d{6}\\.\\d{3}Z\\.\\d{6}\\.jsonl");
                    })
                    .sorted()
                    .toList();
        }
    }
}
