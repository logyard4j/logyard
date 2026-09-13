package com.logyard4j.output.console;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.output.console.style.BuiltInThemes;
import com.logyard4j.output.console.terminal.ColorCapability;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ConsoleSinkFailureTest {
    @Test
    void brokenPipeFailsHealthAndRejectsLaterRecordsWithoutRetrying() {
        AtomicInteger writes = new AtomicInteger();
        ConsoleSink sink = sink(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                writes.incrementAndGet();
                throw new IOException("broken pipe");
            }
        }, false);
        LogEvent event = new LogEvent(1, 2, Level.INFO, "test", null, "message", null,
                AttributeSet.EMPTY, null, 1, "main");

        assertThrows(UncheckedIOException.class, () -> sink.accept(event));
        assertEquals(HealthStatus.FAILED, sink.health("console").status());
        assertEquals("idle", sink.health("console").details().get("io_operation"));
        int attempted = writes.get();
        assertThrows(UncheckedIOException.class, () -> sink.accept(event));
        assertEquals(attempted, writes.get());
        sink.close();
        assertEquals(HealthStatus.FAILED, sink.health("console").status());
    }

    @Test
    void flushFailureRemainsVisibleAfterClose() {
        ConsoleSink sink = sink(new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void flush() throws IOException {
                throw new IOException("flush failed");
            }
        }, false);

        assertThrows(UncheckedIOException.class, sink::flush);
        sink.close();
        assertEquals(HealthStatus.FAILED, sink.health("console").status());
    }

    @Test
    void ownedStreamCloseFailureRemainsVisibleAndCloseIsIdempotent() {
        ConsoleSink sink = sink(new OutputStream() {
            @Override
            public void write(int value) {
            }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        }, true);

        assertThrows(UncheckedIOException.class, sink::close);
        sink.close();
        assertEquals(HealthStatus.FAILED, sink.health("console").status());
        assertEquals("idle", sink.health("console").details().get("io_operation"));
    }

    private static ConsoleSink sink(OutputStream output, boolean closeStream) {
        return new ConsoleSink(new PrintStream(output), false, BuiltInThemes.named("ember"),
                ColorCapability.TRUECOLOR, ZoneOffset.UTC, true, closeStream);
    }
}
