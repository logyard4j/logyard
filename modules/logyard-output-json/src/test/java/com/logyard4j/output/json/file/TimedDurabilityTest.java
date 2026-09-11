package com.logyard4j.output.json.file;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.testing.ManualFlushScheduler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TimedDurabilityTest {
    @Test
    void aSparseRecordIsForcedAtItsScheduledFlushAndOnClose() throws Exception {
        checkTimedForce(false);
    }

    @Test
    void scheduledForceFailureRejectsLaterRecordsAndRemainsVisibleAfterClose() throws Exception {
        checkTimedForce(true);
    }

    private static void checkTimedForce(boolean fail) throws Exception {
        var output = Files.createTempDirectory("logyard-timed-force-").resolve("events.jsonl");
        ManualFlushScheduler scheduler = new ManualFlushScheduler();
        AtomicInteger forces = new AtomicInteger();
        JsonFileSink sink = new JsonFileSink(output, ignored -> "{}", 1_024,
                Duration.ofSeconds(1), false, null, true, scheduler, (path, buffer, append) -> {
                    try {
                        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        return new BufferedFileWriter(path, channel, ByteBuffer.allocate(buffer), 0L, () -> {
                            forces.incrementAndGet();
                            if (fail) throw new IOException("injected force failure");
                            channel.force(false);
                        });
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
        try {
            LogEvent event = new LogEvent(0, 0, Level.INFO, "test", null, "record", null,
                    AttributeSet.EMPTY, null, 1, "main");
            sink.accept(event);
            assertEquals(0, forces.get());
            scheduler.runNext();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (forces.get() == 0 || (fail && sink.health("json").status() != HealthStatus.FAILED)) {
                if (System.nanoTime() >= deadline) throw new AssertionError("timed force did not complete");
                Thread.sleep(5);
            }
            if (fail) {
                assertThrows(IllegalStateException.class, () -> sink.accept(event));
            }
        } finally {
            sink.close();
        }
        assertEquals(fail ? 1 : 2, forces.get());
        if (fail) assertEquals(HealthStatus.FAILED, sink.health("json").status());
        assertEquals("{}\n", Files.readString(output));
    }
}
