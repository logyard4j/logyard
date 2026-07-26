package com.zsumz.logyard.output.json.file.rotation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.output.json.file.lease.FileLease;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ArchiveMaintenanceLifecycleTest {
    @Test
    void zeroTimeoutBeginsShutdownWithoutRequiringImmediateThreadExit() throws Exception {
        Path output = Files.createTempDirectory("logyard-zero-maintenance-close-").resolve("events.jsonl");
        ArchiveMaintenance maintenance =
                ArchiveMaintenance.start(new ArchiveNaming(output), policy(Duration.ZERO), FileLease.acquire(output));

        assertDoesNotThrow(() -> maintenance.close(Duration.ZERO));
        assertTrue(maintenance.closing());
        awaitStopped(maintenance);
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    @Test
    void positiveTimeoutStillDrainsQueuedMaintenanceAndReleasesTheLease() throws Exception {
        Path directory = Files.createTempDirectory("logyard-bounded-maintenance-close-");
        Path output = directory.resolve("events.jsonl");
        Path archive = directory.resolve("events.20260726T000000.000Z.000000.jsonl");
        Files.writeString(archive, "{\"payload\":\"" + "x".repeat(1_000_000) + "\"}\n", StandardCharsets.UTF_8);
        ArchiveMaintenance maintenance = ArchiveMaintenance.start(
                new ArchiveNaming(output),
                policy(Duration.ofSeconds(2), RotationPolicy.Compression.GZIP),
                FileLease.acquire(output));
        maintenance.submit(archive);

        maintenance.close(Duration.ofSeconds(2));

        assertFalse(maintenance.workerAlive());
        assertFalse(Files.exists(archive));
        assertTrue(Files.exists(archive.resolveSibling(archive.getFileName() + ".gz")));
        try (FileLease reacquired = FileLease.acquire(output)) {
            assertEquals(output.toAbsolutePath().normalize(), reacquired.activePath());
        }
    }

    private static RotationPolicy policy(Duration shutdownTimeout) {
        return policy(shutdownTimeout, RotationPolicy.Compression.NONE);
    }

    private static RotationPolicy policy(Duration shutdownTimeout, RotationPolicy.Compression compression) {
        return new RotationPolicy(1_024, 2, compression, shutdownTimeout);
    }

    private static void awaitStopped(ArchiveMaintenance maintenance) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (maintenance.workerAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(maintenance.workerAlive());
    }
}
