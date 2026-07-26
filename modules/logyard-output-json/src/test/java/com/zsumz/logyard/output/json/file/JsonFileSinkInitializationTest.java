package com.zsumz.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class JsonFileSinkInitializationTest {
    private static final EventEncoder ENCODER =
            event -> "{\"sequence\":" + event.attributes().get("sequence") + "}";

    @Test
    void activatedUnusedSinkDoesNotOpenDuringFlushOrClose() throws Exception {
        Path output = Files.createTempDirectory("logyard-unused-file-").resolve("events.jsonl");
        Files.writeString(output, "KEEP-ME\n", StandardCharsets.UTF_8);

        try (JsonFileSink sink = prepared(output, false, null)) {
            sink.activate();
            sink.flush();
            assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));
        }

        assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void flushPersistsBytesAfterTheFirstRecordInitializesTheWriter() throws Exception {
        Path output = Files.createTempDirectory("logyard-used-file-").resolve("events.jsonl");

        try (JsonFileSink sink = prepared(output, false, null)) {
            sink.activate();
            sink.accept(event(7));
            assertEquals("", Files.readString(output, StandardCharsets.UTF_8));
            sink.flush();
            assertEquals("{\"sequence\":7}\n", Files.readString(output, StandardCharsets.UTF_8));
        }
    }

    @Test
    void failedDirectInitializationReleasesItsLeaseWithoutStartingMaintenance() throws Exception {
        Path output = Files.createTempDirectory("logyard-direct-open-failure-").resolve("events.jsonl");
        Files.createDirectory(output);
        String workerName = maintenanceWorkerName(output);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new JsonFileSink(
                        output,
                        ENCODER,
                        1_024,
                        Duration.ZERO,
                        false,
                        rotation()));

        assertTrue(failure.getMessage().contains("regular file"));
        assertFalse(threadAlive(workerName));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void eagerDataFileOpenFailureReleasesTheAlreadyAcquiredLease() throws Exception {
        Path output = Files.createTempDirectory("logyard-eager-open-failure-").resolve("events.jsonl");
        UncheckedIOException openFailure =
                new UncheckedIOException("injected data-file open failure", new java.io.IOException("open failed"));
        RotatingFileWriter writer = new RotatingFileWriter(
                output,
                1_024,
                false,
                rotation(),
                (ignoredPath, ignoredBuffer, ignoredAppend) -> {
                    throw openFailure;
                });

        assertSame(openFailure, assertThrows(UncheckedIOException.class, writer::initializeForDirectUse));
        assertFalse(threadAlive(maintenanceWorkerName(output)));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void zeroTimeoutInitializationRollbackReleasesItsLeaseBeforeReturning() throws Exception {
        Path output = Files.createTempDirectory("logyard-zero-timeout-open-failure-").resolve("events.jsonl");
        UncheckedIOException openFailure =
                new UncheckedIOException("injected data-file open failure", new java.io.IOException("open failed"));
        RotationPolicy zeroTimeoutRotation =
                new RotationPolicy(1_024, 1, RotationPolicy.Compression.NONE, Duration.ZERO);
        RotatingFileWriter writer = new RotatingFileWriter(
                output,
                1_024,
                false,
                zeroTimeoutRotation,
                (ignoredPath, ignoredBuffer, ignoredAppend) -> {
                    throw openFailure;
                });

        assertSame(openFailure, assertThrows(UncheckedIOException.class, writer::initializeForDirectUse));
        assertFalse(threadAlive(maintenanceWorkerName(output)));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void healthyRotatingOutputClosesNormallyWithAZeroShutdownTimeout() throws Exception {
        Path output = Files.createTempDirectory("logyard-zero-timeout-close-").resolve("events.jsonl");
        RotationPolicy zeroTimeoutRotation =
                new RotationPolicy(1_024, 1, RotationPolicy.Compression.NONE, Duration.ZERO);
        JsonFileSink sink = prepared(output, false, zeroTimeoutRotation);
        sink.activate();
        sink.accept(event(1));

        assertDoesNotThrow(sink::close);

        awaitThreadStopped(maintenanceWorkerName(output));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void rejectsAParentThatIsNotADirectoryBeforeAcquiringALease() throws Exception {
        Path directory = Files.createTempDirectory("logyard-parent-validation-");
        Path parent = directory.resolve("not-a-directory");
        Files.writeString(parent, "file", StandardCharsets.UTF_8);
        Path output = parent.resolve("events.jsonl");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new JsonFileSink(output, ENCODER, 1_024, Duration.ZERO, false, null));

        assertTrue(failure.getMessage().contains("parent must be a directory"));
        assertEquals("file", Files.readString(parent, StandardCharsets.UTF_8));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".logyard.lock")));
    }

    @Test
    void closingAfterPreparedFirstUseFailureReleasesEveryResource() throws Exception {
        Path output = Files.createTempDirectory("logyard-prepared-open-failure-").resolve("events.jsonl");
        String workerName = maintenanceWorkerName(output);
        JsonFileSink sink = prepared(output, false, rotation());
        sink.activate();
        Files.createDirectory(output);
        try {
            assertThrows(UncheckedIOException.class, () -> sink.accept(event(1)));
        } finally {
            sink.close();
        }

        assertFalse(threadAlive(workerName));
        Files.delete(output);
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void cleanupFailureIsSuppressedOntoTheInitializationFailure() {
        IllegalStateException initialization = new IllegalStateException("open failed");
        IllegalStateException cleanup = new IllegalStateException("cleanup failed");

        FileWriterInitialization.closeAfterFailure(initialization, () -> {
            throw cleanup;
        });

        assertEquals(1, initialization.getSuppressed().length);
        assertSame(cleanup, initialization.getSuppressed()[0]);
    }

    @Test
    void healthObservesCoherentWriterLifecycleSnapshots() throws Exception {
        Path output = Files.createTempDirectory("logyard-writer-health-").resolve("events.jsonl");
        JsonFileSink sink = prepared(output, false, rotation());
        sink.activate();

        ComponentHealth unopened = sink.health("json");
        assertEquals(HealthStatus.HEALTHY, unopened.status());
        assertEquals(0L, unopened.metrics().get("maintenance_queue_capacity"));

        sink.accept(event(3));
        sink.flush();
        ComponentHealth initialized = sink.health("json");
        assertEquals(HealthStatus.HEALTHY, initialized.status());
        assertEquals(32L, initialized.metrics().get("maintenance_queue_capacity"));
        assertTrue(initialized.metrics().get("maintenance_queue_depth")
                <= initialized.metrics().get("maintenance_queue_capacity"));

        sink.close();
        assertEquals(HealthStatus.STOPPED, sink.health("json").status());
    }

    private static JsonFileSink prepared(Path output, boolean append, RotationPolicy rotation) {
        return JsonFileSink.prepare(output, ENCODER, 1_024, Duration.ofMinutes(1), append, rotation);
    }

    private static RotationPolicy rotation() {
        return new RotationPolicy(1_024, 1, RotationPolicy.Compression.NONE, Duration.ofSeconds(1));
    }

    private static String maintenanceWorkerName(Path output) {
        return "logyard-archive-maintenance-" + output.getFileName();
    }

    private static boolean threadAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private static void awaitThreadStopped(String name) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (threadAlive(name) && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(threadAlive(name));
    }

    private static LogEvent event(int sequence) {
        return new LogEvent(
                0,
                0,
                Level.INFO,
                "test.Logger",
                "test.event",
                "event",
                new Object[0],
                AttributeSet.of("sequence", sequence),
                null,
                1,
                "test");
    }
}
