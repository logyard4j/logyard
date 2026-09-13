package com.logyard4j.output.json.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/** Single-owner byte buffer around a no-follow file channel. */
final class BufferedFileWriter implements ActiveDataFile {
    private final Path path;
    private final WritableByteChannel channel;
    private final ByteBuffer buffer;
    private final DurabilityBarrier durability;
    private long logicalBytes;
    private Phase phase = Phase.OPEN;

    BufferedFileWriter(Path path, WritableByteChannel channel, ByteBuffer buffer, long logicalBytes) {
        this(path, channel, buffer, logicalBytes, DurabilityBarrier.OPERATING_SYSTEM);
    }

    BufferedFileWriter(
            Path path,
            WritableByteChannel channel,
            ByteBuffer buffer,
            long logicalBytes,
            DurabilityBarrier durability) {
        this.path = path;
        this.channel = channel;
        this.buffer = buffer;
        this.durability = durability;
        this.logicalBytes = logicalBytes;
    }

    static BufferedFileWriter open(Path path, int bufferBytes, boolean append) {
        return open(path, bufferBytes, append, false);
    }

    /**
     * Opens the data file, optionally forcing it to storage at the end of every completed flush.
     *
     * <p>{@code fsync} calls {@code FileChannel.force(false)} after an explicit or scheduled
     * flush and on close. It requests file-content persistence; directory metadata and remote
     * filesystem guarantees remain outside this boundary.</p>
     */
    static BufferedFileWriter open(Path path, int bufferBytes, boolean append, boolean fsync) {
        FileChannel channel = null;
        try {
            ByteBuffer buffer = ByteBuffer.allocate(bufferBytes);
            Set<java.nio.file.OpenOption> options = append
                    ? Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
                    : Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            channel = FileChannel.open(path, options);
            FileChannel opened = channel;
            return new BufferedFileWriter(
                    path,
                    opened,
                    buffer,
                    append ? opened.size() : 0L,
                    fsync ? () -> opened.force(false) : DurabilityBarrier.OPERATING_SYSTEM);
        } catch (IOException failure) {
            closeAfterOpenFailure(channel, failure);
            throw new UncheckedIOException("failed to open Logyard JSON output " + path, failure);
        } catch (RuntimeException | Error failure) {
            closeAfterOpenFailure(channel, failure);
            throw failure;
        }
    }

    @Override
    public long logicalBytes() {
        return logicalBytes;
    }

    @Override
    public void write(byte[] record, int length, byte terminator) {
        Objects.checkFromIndexSize(0, length, record.length);
        ensureOpen();
        try {
            int recordBytes = Math.addExact(length, 1);
            if (recordBytes > buffer.capacity()) {
                flushBuffer();
                writeFully(ByteBuffer.wrap(record, 0, length));
                buffer.put(terminator);
            } else {
                if (buffer.remaining() < recordBytes) {
                    flushBuffer();
                }
                buffer.put(record, 0, length).put(terminator);
            }
            logicalBytes = Math.addExact(logicalBytes, recordBytes);
        } catch (IOException failure) {
            discardAndClose(failure);
            throw new UncheckedIOException("failed to write Logyard JSON output " + path, failure);
        }
    }

    @Override
    public void flush() {
        ensureOpen();
        try {
            flushBuffer();
            durability.sync();
        } catch (IOException failure) {
            discardAndClose(failure);
            throw new UncheckedIOException("failed to flush Logyard JSON output " + path, failure);
        }
    }

    @Override
    public void close() {
        if (phase != Phase.OPEN) {
            return;
        }
        IOException failure = null;
        Phase nextPhase = Phase.CLOSED;
        try {
            flushBuffer();
            durability.sync();
        } catch (IOException flushFailure) {
            nextPhase = Phase.FAILED;
            buffer.clear();
            failure = flushFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        phase = nextPhase;
        if (failure != null) {
            throw new UncheckedIOException("failed to close Logyard JSON output " + path, failure);
        }
    }

    private void flushBuffer() throws IOException {
        buffer.flip();
        writeFully(buffer);
        buffer.clear();
    }

    private void writeFully(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private void discardAndClose(IOException failure) {
        phase = Phase.FAILED;
        buffer.clear();
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void ensureOpen() {
        switch (phase) {
            case OPEN -> {
                return;
            }
            case FAILED -> throw new IllegalStateException("Logyard JSON output failed: " + path);
            case CLOSED -> throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }

    private static void closeAfterOpenFailure(FileChannel channel, Throwable failure) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private enum Phase {
        OPEN,
        FAILED,
        CLOSED
    }
}
