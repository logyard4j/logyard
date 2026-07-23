package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class EventSinkCleanupTest {
    @Test
    void closesEachSinkIdentityOnlyOnce() {
        RecordingSink sink = new RecordingSink(null);

        EventSinkCleanup.close(List.of(sink, sink), new IllegalStateException("assembly failed"));

        assertEquals(1, sink.closeCount);
    }

    @Test
    void suppressesCloseFailuresAndContinuesRollback() {
        RuntimeException closeFailure = new IllegalStateException("close failed");
        RecordingSink failing = new RecordingSink(closeFailure);
        RecordingSink following = new RecordingSink(null);
        RuntimeException assemblyFailure = new IllegalArgumentException("assembly failed");

        EventSinkCleanup.close(List.of(failing, following), assemblyFailure);

        assertEquals(1, following.closeCount);
        assertEquals(1, assemblyFailure.getSuppressed().length);
        assertSame(closeFailure, assemblyFailure.getSuppressed()[0]);
    }

    private static final class RecordingSink implements EventSink {
        private final RuntimeException closeFailure;
        private int closeCount;

        private RecordingSink(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
