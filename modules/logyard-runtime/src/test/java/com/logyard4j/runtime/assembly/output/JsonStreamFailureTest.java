package com.logyard4j.runtime.assembly.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.Level;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.diagnostics.HealthContributor;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.runtime.extension.ExtensionRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

final class JsonStreamFailureTest {
    @Test
    void bufferedRecordsStayBatchedUntilExplicitFlushAndFailureStaysVisibleAfterClose() {
        for (String destination : new String[] {"stdout", "stderr"}) {
            Transport transport = new Transport();
            try (EventSink sink = output(destination, "1m", transport)) {
                sink.accept(event("small record"));
                assertEquals(0, transport.writes);
                assertEquals(0, transport.flushes);
                sink.flush();
                assertTrue(transport.writes > 0);

                transport.failWrite = true;
                sink.accept(event("lost record"));
                assertThrows(UncheckedIOException.class, sink::flush);
                assertFailedAndClosed(sink, transport);
            }
        }
    }

    @Test
    void detectsErrorsDuringByteBufferEmissionWithoutWaitingForTheTimer() {
        Transport transport = new Transport();
        transport.failWrite = true;
        try (EventSink sink = output("stdout", "1m", transport)) {
            assertThrows(UncheckedIOException.class, () -> sink.accept(event("x".repeat(12_000))));
            assertFailedAndClosed(sink, transport);
        }
    }

    @Test
    void detectsDefaultPerRecordFlushFailure() {
        Transport transport = new Transport();
        transport.failFlush = true;
        try (EventSink sink = output("stderr", "0s", transport)) {
            assertThrows(UncheckedIOException.class, () -> sink.accept(event("record")));
            assertFailedAndClosed(sink, transport);
        }
    }

    @Test
    void detectsFlushOnlyFailureAtTheSparseTrafficDeadline() {
        Transport transport = new Transport();
        transport.failFlush = true;
        try (EventSink sink = output("stdout", "10ms", transport)) {
            sink.accept(event("sparse record"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (health(sink) != HealthStatus.FAILED && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertFailedAndClosed(sink, transport);
        }
    }

    @Test
    void detectsFailureOnCloseWithoutClosingTheProcessStream() {
        for (boolean writeFailure : new boolean[] {true, false}) {
            Transport transport = new Transport();
            transport.failWrite = writeFailure;
            transport.failFlush = !writeFailure;
            EventSink sink = output("stderr", "1m", transport);
            sink.accept(event("last record"));
            assertThrows(UncheckedIOException.class, sink::close);
            assertFailedAndClosed(sink, transport);
        }
    }

    private static void assertFailedAndClosed(EventSink sink, Transport transport) {
        assertEquals(HealthStatus.FAILED, health(sink));
        int writes = transport.writes;
        assertThrows(IllegalStateException.class, () -> sink.accept(event("rejected")));
        assertThrows(IllegalStateException.class, sink::flush);
        sink.close();
        sink.close();
        assertEquals(HealthStatus.FAILED, health(sink));
        assertEquals(writes, transport.writes);
        assertFalse(transport.closed);
    }

    private static HealthStatus health(EventSink sink) {
        return ((HealthContributor) sink).health("json").status();
    }

    private static EventSink output(String destination, String interval, Transport transport) {
        LogyardConfig config = LogyardConfigLoader.parse("""
                schema = 1
                [service]
                name = "stream-test"
                [delivery]
                mode = "sync"
                [outputs.json]
                type = "stream"
                stream = "%s"
                flush = "%s"
                """.formatted(destination, interval), "stream.toml", Path.of("."), Map.of());
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream stream = new PrintStream(transport, false, StandardCharsets.UTF_8);
        try {
            if ("stdout".equals(destination)) {
                System.setOut(stream);
            } else {
                System.setErr(stream);
            }
            return OutputFactory.prepare(config, config.outputs().get("json"),
                    new ExtensionRegistry(Map.of(), Map.of(), Map.of(), Map.of())).sink();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null,
                AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class Transport extends OutputStream {
        private int writes;
        private int flushes;
        private boolean closed;
        private boolean failWrite;
        private boolean failFlush;

        @Override
        public void write(int value) throws IOException {
            writes++;
            if (failWrite) {
                throw new IOException("broken pipe");
            }
        }

        @Override
        public void flush() throws IOException {
            flushes++;
            if (failFlush) {
                throw new IOException("flush failed");
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
