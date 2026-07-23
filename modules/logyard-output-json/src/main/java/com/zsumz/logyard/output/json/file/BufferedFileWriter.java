package com.zsumz.logyard.output.json.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Single-owner byte buffer around a no-follow file channel. */
final class BufferedFileWriter implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final ByteBuffer buffer;
    private long logicalBytes;
    private boolean closed;

    private BufferedFileWriter(Path path, FileChannel channel, ByteBuffer buffer, long logicalBytes) {
        this.path = path;
        this.channel = channel;
        this.buffer = buffer;
        this.logicalBytes = logicalBytes;
    }

    static BufferedFileWriter open(Path path, int bufferBytes, boolean append) {
        try {
            Set<java.nio.file.OpenOption> options = append
                    ? Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
                    : Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            FileChannel channel = FileChannel.open(path, options);
            return new BufferedFileWriter(path, channel, ByteBuffer.allocate(bufferBytes), append ? channel.size() : 0L);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to open Logyard JSON output " + path, failure);
        }
    }

    long logicalBytes() {
        return logicalBytes;
    }

    void write(byte[] record, byte terminator) {
        ensureOpen();
        try {
            int recordBytes = Math.addExact(record.length, 1);
            if (recordBytes > buffer.capacity()) {
                flushBuffer();
                writeFully(ByteBuffer.wrap(record));
                buffer.put(terminator);
            } else {
                if (buffer.remaining() < recordBytes) {
                    flushBuffer();
                }
                buffer.put(record).put(terminator);
            }
            logicalBytes = Math.addExact(logicalBytes, recordBytes);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to write Logyard JSON output " + path, failure);
        }
    }

    void flush() {
        ensureOpen();
        try {
            flushBuffer();
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to flush Logyard JSON output " + path, failure);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            flushBuffer();
        } catch (IOException flushFailure) {
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }
}
