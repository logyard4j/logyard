package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesSinkTest {
    @Test
    void customEncoderCanInvokeAcceptFlushAndCloseAcrossThreadsWithoutDeadlock() {
        StringWriter writer = new StringWriter();
        ExecutorService callbacks = Executors.newSingleThreadExecutor();
        AtomicReference<JsonLinesSink> sinkReference = new AtomicReference<>();
        AtomicBoolean outer = new AtomicBoolean(true);
        EventEncoder encoder = event -> {
            if (outer.compareAndSet(true, false)) {
                invoke(callbacks, () -> sinkReference.get().accept(event("nested")));
                invoke(callbacks, () -> sinkReference.get().flush());
                invoke(callbacks, () -> sinkReference.get().close());
            }
            return event.messageTemplate();
        };
        JsonLinesSink sink = new JsonLinesSink(writer, encoder, Duration.ZERO, false);
        sinkReference.set(sink);
        try {
            assertThrows(IllegalStateException.class, () -> sink.accept(event("outer")));
        } finally {
            callbacks.shutdownNow();
        }
        assertEquals("nested\n", writer.toString());
    }

    private static LogEvent event(String message) {
        return new LogEvent(0L, 0L, Level.INFO, "test.Logger", "test", message, null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static void invoke(ExecutorService executor, Runnable operation) {
        try {
            executor.submit(operation).get(2L, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError("cross-thread sink operation did not complete", failure);
        }
    }
}
