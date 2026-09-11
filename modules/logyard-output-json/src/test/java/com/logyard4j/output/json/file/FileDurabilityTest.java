package com.logyard4j.output.json.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.encoding.EventEncoder;
import com.logyard4j.output.json.file.rotation.RotationPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verifies that opt-in durable flushing forces the data file on every flush path and fails closed. */
final class FileDurabilityTest {
    private static final EventEncoder ENCODER = event -> "{\"event\":\"" + event.messageTemplate() + "\"}";

    @Test
    void explicitFlushRotationCloseAndShutdownCloseEachForceTheDataFile() throws Exception {
        Path directory = Files.createTempDirectory("logyard-fsync-paths-");
        Path output = directory.resolve("events.jsonl");
        AtomicInteger forces = new AtomicInteger();
        RotatingFileWriter writer = new RotatingFileWriter(
                output,
                1_024,
                false,
                new RotationPolicy(1_024, 5, RotationPolicy.Compression.NONE, Duration.ofSeconds(3)),
                forcingOpener(forces));
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("first"), (byte) '\n');
            assertEquals(0, forces.get());

            writer.flush();
            assertEquals(1, forces.get());

            writer.writeRecord(padded(987), (byte) '\n');
            writer.writeRecord(record("after"), (byte) '\n');
            assertEquals(2, forces.get());
        } finally {
            writer.close();
        }

        assertEquals(3, forces.get());
        assertEquals(json("after"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void ordinaryBufferedWriterPersistsRecordsWithoutDurabilityOptIn() throws Exception {
        Path directory = Files.createTempDirectory("logyard-fsync-absent-");
        Path output = directory.resolve("events.jsonl");
        AtomicInteger opens = new AtomicInteger();
        RotatingFileWriter writer = new RotatingFileWriter(
                output, 1_024, false, null, (path, bufferBytes, append) -> {
                    opens.incrementAndGet();
                    return BufferedFileWriter.open(path, bufferBytes, append, false);
                });
        try {
            writer.initializeForDirectUse();
            writer.writeRecord(record("first"), (byte) '\n');
            writer.flush();
        } finally {
            writer.close();
        }

        assertEquals(1, opens.get());
        assertEquals(json("first"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void aFailedForceIsFailClosedLikeAFailedFlush() throws Exception {
        Path output = Files.createTempDirectory("logyard-fsync-failure-").resolve("events.jsonl");
        RecordingChannel channel = new RecordingChannel();
        BufferedFileWriter dataFile = new BufferedFileWriter(
                output, channel, ByteBuffer.allocate(64), 0L, FileDurabilityTest::rejectSync);
        RotatingFileWriter writer =
                new RotatingFileWriter(output, 1_024, false, null, (path, bufferBytes, append) -> dataFile);
        writer.initializeForDirectUse();
        writer.writeRecord(record("first"), (byte) '\n');

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, writer::flush);

        assertEquals("FAILED", writer.healthSnapshot().writerState());
        assertEquals(failure.getClass().getName(), writer.healthSnapshot().writerFailureType());
        IllegalStateException later = assertThrows(
                IllegalStateException.class, () -> writer.writeRecord(record("later"), (byte) '\n'));
        assertSame(failure, later.getCause());
        assertEquals(1, channel.closeCalls.get());
        int writesAfterFailure = channel.writeCalls.get();
        writer.close();
        assertEquals(writesAfterFailure, channel.writeCalls.get());
        assertEquals(1, channel.closeCalls.get());
    }

    @Test
    void aFailedForceOnCloseIsReportedAndClosesTheChannelOnce() throws Exception {
        Path output = Files.createTempDirectory("logyard-fsync-close-failure-").resolve("events.jsonl");
        RecordingChannel channel = new RecordingChannel();
        BufferedFileWriter dataFile = new BufferedFileWriter(
                output, channel, ByteBuffer.allocate(64), 0L, FileDurabilityTest::rejectSync);
        RotatingFileWriter writer =
                new RotatingFileWriter(output, 1_024, false, null, (path, bufferBytes, append) -> dataFile);
        writer.initializeForDirectUse();
        writer.writeRecord(record("first"), (byte) '\n');

        assertThrows(UncheckedIOException.class, writer::close);
        assertEquals(UncheckedIOException.class.getName(), writer.healthSnapshot().writerFailureType());

        assertEquals(1, channel.closeCalls.get());
        writer.close();
        assertEquals(1, channel.closeCalls.get());
    }

    @Test
    void aDurableSinkPublishesItsRecordsThroughTheRealChannel() throws Exception {
        Path output = Files.createTempDirectory("logyard-fsync-sink-").resolve("events.jsonl");
        try (JsonFileSink sink = new JsonFileSink(output, ENCODER, 1_024, Duration.ZERO, false, null, true)) {
            sink.accept(event("durable"));
            sink.flush();
            assertEquals(json("durable"), Files.readString(output, StandardCharsets.UTF_8));
        }
    }

    @Test
    void aDurablePreparedSinkLeavesExistingContentUntouchedUntilItIsActivated() throws Exception {
        Path output = Files.createTempDirectory("logyard-fsync-prepared-").resolve("events.jsonl");
        Files.writeString(output, "KEEP-ME\n", StandardCharsets.UTF_8);
        try (JsonFileSink sink = JsonFileSink.prepare(output, ENCODER, 1_024, Duration.ZERO, false, null, true)) {
            assertEquals("KEEP-ME\n", Files.readString(output, StandardCharsets.UTF_8));
            sink.activate();
            sink.accept(event("durable"));
        }
        assertEquals(json("durable"), Files.readString(output, StandardCharsets.UTF_8));
    }

    @Test
    void aDurablyOpenedFileForcesWithoutLosingBufferedBytes() throws Exception {
        Path output = Files.createTempDirectory("logyard-fsync-open-").resolve("events.jsonl");
        BufferedFileWriter writer = BufferedFileWriter.open(output, 1_024, false, true);
        try {
            writer.write(record("first"), (byte) '\n');
            writer.flush();
            assertEquals(json("first"), Files.readString(output, StandardCharsets.UTF_8));
            writer.write(record("second"), (byte) '\n');
        } finally {
            writer.close();
        }

        assertEquals(json("first") + json("second"), Files.readString(output, StandardCharsets.UTF_8));
    }

    private static DataFileOpener forcingOpener(AtomicInteger forces) {
        return (path, bufferBytes, append) -> {
            try {
                FileChannel channel = FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
                return new BufferedFileWriter(
                        path,
                        channel,
                        ByteBuffer.allocate(bufferBytes),
                        append ? channel.size() : 0L,
                        () -> {
                            forces.incrementAndGet();
                            channel.force(false);
                        });
            } catch (IOException failure) {
                throw new UncheckedIOException("test opener failed", failure);
            }
        };
    }

    private static void rejectSync() throws IOException {
        throw new IOException("injected force failure");
    }

    private static byte[] record(String event) {
        return ("{\"event\":\"" + event + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] padded(int width) {
        return record("x".repeat(width));
    }

    private static String json(String event) {
        return "{\"event\":\"" + event + "\"}\n";
    }

    private static LogEvent event(String template) {
        return new LogEvent(
                0, 0, Level.INFO, "test.Logger", "test.event", template, new Object[0],
                AttributeSet.EMPTY, null, 1, "test");
    }

    private static final class RecordingChannel implements WritableByteChannel {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private Phase phase = Phase.OPEN;

        @Override
        public int write(ByteBuffer source) {
            writeCalls.incrementAndGet();
            int copied = source.remaining();
            byte[] bytes = new byte[copied];
            source.get(bytes);
            written.writeBytes(bytes);
            return copied;
        }

        @Override
        public boolean isOpen() {
            return phase == Phase.OPEN;
        }

        @Override
        public void close() {
            if (phase == Phase.OPEN) {
                phase = Phase.CLOSED;
                closeCalls.incrementAndGet();
            }
        }

        private enum Phase {
            OPEN,
            CLOSED
        }
    }
}
