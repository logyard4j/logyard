package com.logyard4j.logyard.output.json.stream;

import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesSinkCloseFailureTest {
    @Test
    void uncheckedCloseFailureIsStickyAndCleanupIsNotRetried() {
        IllegalStateException failure = new IllegalStateException("close failed");
        CloseFailureWriter writer = new CloseFailureWriter(failure);
        JsonLinesSink sink = new JsonLinesSink(writer, event -> "{}", Duration.ofSeconds(1L), true);

        assertSame(failure, assertThrows(IllegalStateException.class, sink::close));

        assertEquals(HealthStatus.FAILED, sink.health("json").status());
        assertEquals(IllegalStateException.class.getName(), sink.health("json").details().get("writer_failure"));
        assertEquals(1, writer.closes.get());
        sink.close();
        assertEquals(1, writer.closes.get());
    }

    private static final class CloseFailureWriter extends Writer {
        private final IllegalStateException failure;
        private final AtomicInteger closes = new AtomicInteger();

        private CloseFailureWriter(IllegalStateException failure) {
            this.failure = failure;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            throw failure;
        }
    }
}
