package com.zsumz.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Verifies that a maximum active-file age rotates between complete records like the size limit. */
final class FileRotationIntervalTest {
    private static final Duration INTERVAL = Duration.ofMillis(200);
    private static final long HUGE_SIZE = 1L << 30;
    private final AtomicLong clock = new AtomicLong();

    @Test
    void rotatesTheActiveFileOnceItOutlivesItsInterval() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-rotate-");
        Path output = directory.resolve("events.jsonl");
        RotatingFileWriter writer = writer(output, false, policy(HUGE_SIZE, 10, INTERVAL));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("first"), (byte) '\n');
            advancePast(INTERVAL);
            writer.writeRecord(record("second"), (byte) '\n');
        } finally {
            writer.close();
        }

        List<Path> archives = plainArchives(directory);
        assertEquals(1, archives.size());
        assertEquals(json("first"), Files.readString(archives.get(0), StandardCharsets.UTF_8));
        assertEquals(json("second"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void anIntervalRotationRestartsTheAgeOfTheReplacementFile() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-restart-");
        Path output = directory.resolve("events.jsonl");
        RotatingFileWriter writer = writer(output, false, policy(HUGE_SIZE, 10, INTERVAL));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("first"), (byte) '\n');
            advancePast(INTERVAL);
            writer.writeRecord(record("second"), (byte) '\n');
            writer.writeRecord(record("third"), (byte) '\n');
        } finally {
            writer.close();
        }

        assertEquals(1, plainArchives(directory).size());
        assertEquals(json("second") + json("third"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyActiveFileIsNeverRotatedByAgeAlone() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-empty-");
        Path output = directory.resolve("events.jsonl");
        RotatingFileWriter writer = writer(output, false, policy(HUGE_SIZE, 10, INTERVAL));
        try {
            writer.initializeForDirectUse();
            advancePast(INTERVAL);
            writer.writeRecord(record("first"), (byte) '\n');
        } finally {
            writer.close();
        }

        assertEquals(List.of(), plainArchives(directory));
        assertEquals(json("first"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void aResumedFileInheritsItsAgeFromTheLastModifiedTime() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-resume-");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(output, json("earlier"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(output, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        RotatingFileWriter writer = writer(output, true, policy(HUGE_SIZE, 10, Duration.ofHours(1)));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("resumed"), (byte) '\n');
        } finally {
            writer.close();
        }

        List<Path> archives = plainArchives(directory);
        assertEquals(1, archives.size());
        assertEquals(json("earlier"), Files.readString(archives.get(0), StandardCharsets.UTF_8));
        assertEquals(json("resumed"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void aFreshFileDoesNotInheritTheAgeOfTheContentItTruncates() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-truncate-");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(output, json("stale"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(output, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        RotatingFileWriter writer = writer(output, false, policy(HUGE_SIZE, 10, Duration.ofHours(1)));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("first"), (byte) '\n');
            writer.writeRecord(record("second"), (byte) '\n');
        } finally {
            writer.close();
        }

        assertEquals(List.of(), plainArchives(directory));
        assertEquals(json("first") + json("second"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void theSizeLimitStillRotatesWhenTheIntervalHasNotElapsed() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-size-wins-");
        Path output = directory.resolve("events.jsonl");
        RotatingFileWriter writer = writer(output, false, policy(1_024, 10, Duration.ofHours(1)));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(("{\"event\":\"" + "x".repeat(1_011) + "\"}").getBytes(StandardCharsets.UTF_8), (byte) '\n');
            writer.writeRecord(record("after"), (byte) '\n');
        } finally {
            writer.close();
        }

        assertEquals(1, plainArchives(directory).size());
        assertEquals(json("after"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void intervalArchivesAreCompressedAndRetainedLikeSizeArchives() throws Exception {
        Path directory = Files.createTempDirectory("logyard-interval-archives-");
        Path output = directory.resolve("events.jsonl");
        RotatingFileWriter writer = writer(
                output, false, policy(HUGE_SIZE, 1, RotationPolicy.Compression.GZIP, INTERVAL));
        try {
            writer.initializeForDirectUse();
            for (int index = 0; index < 3; index++) {
                writer.writeRecord(record("record-" + index), (byte) '\n');
                advancePast(INTERVAL);
            }
            writer.writeRecord(record("last"), (byte) '\n');
        } finally {
            writer.close();
        }

        awaitRetention(directory);
        assertEquals(List.of(), plainArchives(directory));
        assertEquals(json("last"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void aPolicyRejectsANonPositiveOrOverlongInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(HUGE_SIZE, 1, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(HUGE_SIZE, 1, Duration.ofMillis(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy(HUGE_SIZE, 1, Duration.ofDays(366)));
        assertTrue(policy(HUGE_SIZE, 1, RotationPolicy.MAXIMUM_AGE).maximumAge().equals(Duration.ofDays(365)));
    }

    private RotatingFileWriter writer(Path output, boolean append, RotationPolicy policy) {
        return new RotatingFileWriter(output, 1_024, append, policy, BufferedFileWriter::open, clock::get);
    }

    private static RotationPolicy policy(long maximumBytes, int keep, Duration interval) {
        return policy(maximumBytes, keep, RotationPolicy.Compression.NONE, interval);
    }

    private static RotationPolicy policy(
            long maximumBytes, int keep, RotationPolicy.Compression compression, Duration interval) {
        return new RotationPolicy(maximumBytes, keep, compression, Duration.ofSeconds(3), interval);
    }

    private static byte[] record(String event) {
        return ("{\"event\":\"" + event + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String json(String event) {
        return "{\"event\":\"" + event + "\"}\n";
    }

    private void advancePast(Duration interval) {
        clock.addAndGet(interval.plusMillis(50).toNanos());
    }

    private static void awaitRetention(Path directory) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (gzipArchives(directory).size() != 1) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("retention did not settle at one archive: " + gzipArchives(directory));
            }
            Thread.sleep(10L);
        }
    }

    private static List<Path> plainArchives(Path directory) throws IOException {
        return matching(directory, "events\\.\\d{8}T\\d{6}\\.\\d{3}Z\\.\\d{6}\\.jsonl");
    }

    private static List<Path> gzipArchives(Path directory) throws IOException {
        return matching(directory, "events\\.\\d{8}T\\d{6}\\.\\d{3}Z\\.\\d{6}\\.jsonl\\.gz");
    }

    private static List<Path> matching(Path directory, String pattern) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches(pattern)).sorted().toList();
        }
    }
}
