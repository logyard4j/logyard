package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.io.Writer;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonLinesFatalFailureTest {
    @Test
    void fatalJvmFailureIsRethrownWithoutReplacement() {
        LinkageError failure = new LinkageError("fatal writer failure");
        JsonLinesSink sink = new JsonLinesSink(
                new FatalWriter(failure), LogEvent::messageTemplate, Duration.ofSeconds(1L), false);

        assertSame(failure, assertThrows(LinkageError.class, () -> sink.accept(event())));
    }

    private static LogEvent event() {
        return new LogEvent(
                0L, 0L, Level.INFO, "test.Logger", "test", "record", null, AttributeSet.EMPTY, null, 1L, "test");
    }

    private static final class FatalWriter extends Writer {
        private final LinkageError failure;

        private FatalWriter(LinkageError failure) {
            this.failure = failure;
        }

        @Override
        public void write(char[] characters, int offset, int length) {
            throw failure;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
