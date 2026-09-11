package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.console.style.BuiltInThemes;
import com.zsumz.logyard.output.console.terminal.ColorCapability;
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
    }

    private static ConsoleSink sink(OutputStream output, boolean closeStream) {
        return new ConsoleSink(new PrintStream(output), false, BuiltInThemes.named("ember"),
                ColorCapability.TRUECOLOR, ZoneOffset.UTC, true, closeStream);
    }
}
