package com.logyard4j.logyard.output.json.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ActiveDataFileFailureTest {
    @Test
    void writeFailureBeforeProgressIsTerminal() throws Exception {
        Path output = Files.createTempDirectory("logyard-write-failure-").resolve("events.jsonl");
        ControlledDataFile dataFile = ControlledDataFile.failingWrite();
        RotatingFileWriter writer = writer(output, (path, bufferBytes, append) -> dataFile);
        writer.initializeForDirectUse();

        UncheckedIOException failure = assertThrows(
                UncheckedIOException.class,
                () -> writer.writeRecord(bytes("record"), (byte) '\n'));

        assertTerminal(writer, failure);
        assertEquals(1, dataFile.closeCalls.get());
        writer.close();
        assertEquals(1, dataFile.closeCalls.get());
    }

    @Test
    void partialDirectWriteFailureDiscardsTheBufferAndClosesTheChannelOnce() throws Exception {
        Path output = Files.createTempDirectory("logyard-partial-write-").resolve("events.jsonl");
        PartialFailureChannel channel = new PartialFailureChannel(2);
        BufferedFileWriter dataFile = new BufferedFileWriter(output, channel, ByteBuffer.allocate(4), 0L);
        RotatingFileWriter writer = writer(output, (path, bufferBytes, append) -> dataFile);
        writer.initializeForDirectUse();

        UncheckedIOException failure = assertThrows(
                UncheckedIOException.class,
                () -> writer.writeRecord(bytes("record"), (byte) '\n'));

        assertTerminal(writer, failure);
        assertEquals("re", channel.contents());
        assertEquals(1, channel.closeCalls.get());
        int writesAfterFailure = channel.writeCalls.get();
        writer.close();
        assertEquals(writesAfterFailure, channel.writeCalls.get());
        assertEquals(1, channel.closeCalls.get());
    }

    @Test
    void partialFlushFailureNeverRetriesUncertainBufferedBytes() throws Exception {
        Path output = Files.createTempDirectory("logyard-partial-flush-").resolve("events.jsonl");
        PartialFailureChannel channel = new PartialFailureChannel(3);
        BufferedFileWriter dataFile = new BufferedFileWriter(output, channel, ByteBuffer.allocate(32), 0L);
        RotatingFileWriter writer = writer(output, (path, bufferBytes, append) -> dataFile);
        writer.initializeForDirectUse();
        writer.writeRecord(bytes("record"), (byte) '\n');

        UncheckedIOException failure = assertThrows(UncheckedIOException.class, writer::flush);

        assertTerminal(writer, failure);
        assertEquals("rec", channel.contents());
        int writesAfterFailure = channel.writeCalls.get();
        writer.close();
        assertEquals(writesAfterFailure, channel.writeCalls.get());
        assertEquals(1, channel.closeCalls.get());
    }

    @Test
    void rotationCloseFlushFailureIsTerminalAndDoesNotOpenAReplacement() throws Exception {
        Path output = Files.createTempDirectory("logyard-rotation-close-failure-").resolve("events.jsonl");
        PartialFailureChannel channel = new PartialFailureChannel(3);
        BufferedFileWriter dataFile = new BufferedFileWriter(output, channel, ByteBuffer.allocate(2_048), 0L);
        AtomicInteger opens = new AtomicInteger();
        RotatingFileWriter writer = new RotatingFileWriter(
                output,
                1_024,
                false,
                new RotationPolicy(1_024, 2, RotationPolicy.Compression.NONE, Duration.ofSeconds(1)),
                (path, bufferBytes, append) -> {
                    opens.incrementAndGet();
                    return dataFile;
                });
        writer.initializeForDirectUse();
        writer.writeRecord(bytes("x".repeat(1_023)), (byte) '\n');

        UncheckedIOException failure = assertThrows(
                UncheckedIOException.class,
                () -> writer.writeRecord(bytes("next"), (byte) '\n'));

        assertTerminal(writer, failure);
        assertEquals(1, opens.get());
        assertEquals("xxx", channel.contents());
        assertEquals(1, channel.closeCalls.get());
        int writesAfterFailure = channel.writeCalls.get();
        writer.close();
        assertEquals(writesAfterFailure, channel.writeCalls.get());
        assertEquals(1, channel.closeCalls.get());
    }

    private static RotatingFileWriter writer(Path output, DataFileOpener opener) {
        return new RotatingFileWriter(output, 1_024, false, null, opener);
    }

    private static void assertTerminal(RotatingFileWriter writer, RuntimeException primary) {
        assertEquals("FAILED", writer.healthSnapshot().writerState());
        assertEquals(primary.getClass().getName(), writer.healthSnapshot().writerFailureType());
        IllegalStateException later = assertThrows(
                IllegalStateException.class,
                () -> writer.writeRecord(bytes("later"), (byte) '\n'));
        assertSame(primary, later.getCause());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class ControlledDataFile implements ActiveDataFile {
        private final boolean failWrite;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private long logicalBytes;

        private ControlledDataFile(boolean failWrite) {
            this.failWrite = failWrite;
        }

        static ControlledDataFile failingWrite() {
            return new ControlledDataFile(true);
        }

        @Override
        public long logicalBytes() {
            return logicalBytes;
        }

        @Override
        public void write(byte[] record, int length, byte terminator) {
            if (failWrite) {
                throw ioFailure("write failed");
            }
            logicalBytes += length + 1L;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class PartialFailureChannel implements WritableByteChannel {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private int bytesBeforeFailure;
        private boolean open = true;

        private PartialFailureChannel(int bytesBeforeFailure) {
            this.bytesBeforeFailure = bytesBeforeFailure;
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            writeCalls.incrementAndGet();
            if (bytesBeforeFailure == 0) {
                throw new IOException("injected partial-write failure");
            }
            int copied = Math.min(source.remaining(), bytesBeforeFailure);
            byte[] bytes = new byte[copied];
            source.get(bytes);
            written.writeBytes(bytes);
            bytesBeforeFailure -= copied;
            return copied;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                closeCalls.incrementAndGet();
            }
        }

        String contents() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    private static UncheckedIOException ioFailure(String message) {
        return new UncheckedIOException(message, new IOException(message));
    }
}
