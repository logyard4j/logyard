package com.zsumz.logyard.compare;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/** Writes shared or native encoding, then timestamps successful writes to an actual unbuffered file stream. */
public final class MeasuredDestination implements AutoCloseable {
    private enum Phase { WARMUP, MEASUREMENT, CLOSED }
    private final RunOptions options;
    private final List<String> fields;
    private OutputStream output;
    private Phase phase = Phase.WARMUP;
    private CountDownLatch barrier = new CountDownLatch(1);
    private final BitSet seen;
    private final long[] completed;
    private long bytes;
    private long lastWrite;
    private String failure;

    public MeasuredDestination(RunOptions options) throws IOException {
        this.options = options;
        fields = options.fieldNames();
        Files.createDirectories(options.output().toAbsolutePath().getParent());
        output = Files.newOutputStream(options.output());
        seen = new BitSet(options.events());
        completed = new long[options.events()];
    }

    public synchronized void beginMeasurement() throws IOException {
        output.close();
        output = Files.newOutputStream(options.output());
        barrier = new CountDownLatch(1);
        phase = Phase.MEASUREMENT;
    }

    public synchronized void accept(String message, String level, Function<String, ?> attributes) {
        try {
            if (options.nativeJson()) throw new IllegalStateException("native encoding was bypassed");
            int identity = prepare(message);
            if (identity >= 0) write(identity, encode(message, level, attributes));
        } catch (RuntimeException | IOException error) {
            throw failed(error);
        }
    }

    public synchronized <E> void acceptEncoded(String message, E event, Function<E, byte[]> encoder) {
        try {
            if (!options.nativeJson()) throw new IllegalStateException("unexpected native encoding");
            int identity = prepare(message);
            if (identity >= 0) write(identity, encoder.apply(event));
        } catch (RuntimeException | IOException error) {
            throw failed(error);
        }
    }

    private int prepare(String message) {
        if (phase == Phase.CLOSED) throw new IllegalStateException("delivery after close");
        if (message.equals(Workload.BARRIER)) {
            barrier.countDown();
            return -1;
        }
        int identity = Integer.parseInt(message, 0, 8, 16);
        if (identity < 0 || identity >= completed.length) throw new IllegalStateException("unknown event identity");
        if (phase == Phase.MEASUREMENT) {
            if (seen.get(identity)) throw new IllegalStateException("duplicate event identity");
            if (seen.isEmpty() && options.stallMillis() > 0) park(TimeUnit.MILLISECONDS.toNanos(options.stallMillis()));
            if (options.delayMicros() > 0) park(TimeUnit.MICROSECONDS.toNanos(options.delayMicros()));
        }
        return identity;
    }

    private void write(int identity, byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded[encoded.length - 1] != '\n') {
            throw new IllegalStateException("encoder did not produce a framed record");
        }
        output.write(encoded);
        if (phase == Phase.MEASUREMENT) {
            long now = System.nanoTime();
            completed[identity] = now;
            seen.set(identity);
            bytes += encoded.length;
            lastWrite = now;
        }
    }

    private RuntimeException failed(Exception error) {
        failure = error.toString();
        return error instanceof IOException io ? new UncheckedIOException(io) : (RuntimeException) error;
    }

    public boolean awaitBarrier(long timeoutMillis) throws InterruptedException {
        return barrier.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public synchronized void requireHealthy() {
        if (failure != null) throw new IllegalStateException("destination failed: " + failure);
    }

    public synchronized long[] completionTimes() {
        return completed.clone();
    }

    public synchronized long bytes() {
        return bytes;
    }

    public synchronized long lastWrite() {
        return lastWrite;
    }

    private byte[] encode(String message, String level, Function<String, ?> attributes) {
        if (options.format().equals("text")) return (level + ' ' + message + '\n').getBytes(StandardCharsets.UTF_8);
        StringBuilder json = new StringBuilder(256).append("{\"level\":");
        JsonText.append(json, level);
        json.append(",\"message\":");
        JsonText.append(json, message);
        json.append(",\"attributes\":{");
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) json.append(',');
            String key = fields.get(index);
            Object value = attributes.apply(key);
            if (value == null) throw new IllegalStateException("missing captured field " + key);
            JsonText.append(json, key);
            json.append(':');
            JsonText.append(json, value.toString());
        }
        return json.append("}}\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void park(long nanos) {
        long deadline = System.nanoTime() + nanos;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
    }

    @Override
    public synchronized void close() throws IOException {
        output.close();
        phase = Phase.CLOSED;
        requireHealthy();
    }
}
