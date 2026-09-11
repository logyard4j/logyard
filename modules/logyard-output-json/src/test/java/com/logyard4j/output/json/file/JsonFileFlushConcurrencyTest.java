package com.logyard4j.output.json.file;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.json.testing.FlushWorkerAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonFileFlushConcurrencyTest {
    @Test
    void twoBlockedFilesDoNotStarveAnIndependentFastFile() throws Exception {
        Path directory = Files.createTempDirectory("logyard-file-flush-concurrency-");
        CountDownLatch blocked = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fast = new CountDownLatch(1);
        GateDataFile firstFile = new GateDataFile(blocked, release);
        GateDataFile secondFile = new GateDataFile(blocked, release);
        GateDataFile fastFile = new GateDataFile(fast, new CountDownLatch(0));
        JsonFileSink first = sink(directory, "first.jsonl", firstFile);
        JsonFileSink second = sink(directory, "second.jsonl", secondFile);
        JsonFileSink third = sink(directory, "third.jsonl", fastFile);
        try {
            first.accept(event("first"));
            second.accept(event("second"));
            assertTrue(blocked.await(2L, TimeUnit.SECONDS), "two file flushes did not block");

            third.accept(event("fast"));
            assertTrue(fast.await(2L, TimeUnit.SECONDS), "blocked files starved the fast file");
        } finally {
            release.countDown();
            first.close();
            second.close();
            third.close();
        }

        assertEquals(1, firstFile.flushes.get());
        assertEquals(1, secondFile.flushes.get());
        assertEquals(1, fastFile.flushes.get());
        FlushWorkerAssertions.awaitNoFlushWorkers();
    }

    private static JsonFileSink sink(Path directory, String filename, ActiveDataFile dataFile) {
        return new JsonFileSink(
                directory.resolve(filename),
                LogEvent::messageTemplate,
                1_024,
                Duration.ofMillis(1L),
                false,
                null,
                true,
                com.logyard4j.output.json.flush.FlushScheduler.shared(),
                (path, bufferBytes, append) -> dataFile);
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class GateDataFile implements ActiveDataFile {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicInteger flushes = new AtomicInteger();
        private long bytes;

        private GateDataFile(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public long logicalBytes() {
            return bytes;
        }

        @Override
        public void write(byte[] record, byte terminator) {
            bytes += record.length + 1L;
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
            entered.countDown();
            awaitUninterruptibly(release);
        }

        @Override
        public void close() {
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
