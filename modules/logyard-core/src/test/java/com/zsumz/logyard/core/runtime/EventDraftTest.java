package com.zsumz.logyard.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.ingress.IngressMetadata;

import org.junit.jupiter.api.Test;

final class EventDraftTest {
    @Test
    void capturesExternalTimestampAndThreadIdentity() {
        EventDraft draft = new EventDraft(
                "com.acme.Worker",
                Level.WARN,
                "job.failed",
                "Job {} failed",
                new Object[] {42},
                AttributeSet.of("attempt", 3),
                new IllegalStateException("unavailable"),
                IngressMetadata.source(1_234_567_890L, 17L, "logical-worker"));

        LogEvent event = draft.capture();

        assertEquals(1_234_567_890L, event.timestampMillis());
        assertTrue(event.observedTimestampUnixNanos() > 0L);
        assertEquals(17L, event.threadId());
        assertEquals("logical-worker", event.threadName());
        assertEquals(Level.WARN, event.level());
        assertEquals("com.acme.Worker", event.loggerName());
        assertEquals("job.failed", event.eventName());
        assertEquals("Job 42 failed", event.renderedMessage());
        assertEquals(3, event.attributes().get("attempt"));
        assertEquals("java.lang.IllegalStateException", event.exception().type());
    }

    @Test
    void marksExternalThreadIdUnknownWhenOnlyItsNameIsAvailable() {
        EventDraft draft = new EventDraft(
                "com.acme.Worker",
                Level.INFO,
                null,
                "started",
                null,
                AttributeSet.EMPTY,
                null,
                IngressMetadata.source(42L, "logical-worker"));

        LogEvent event = draft.capture();

        assertEquals(-1L, event.threadId());
        assertEquals("logical-worker", event.threadName());
    }

    @Test
    void capturesTheCurrentThreadWhenNoSourceMetadataIsPresent() {
        EventDraft draft = new EventDraft(
                "com.acme.Worker",
                Level.DEBUG,
                null,
                "started",
                null,
                AttributeSet.EMPTY,
                null,
                IngressMetadata.current());

        LogEvent event = draft.capture();
        Thread currentThread = Thread.currentThread();

        assertTrue(event.timestampMillis() > 0L);
        assertEquals(currentThread.threadId(), event.threadId());
        assertEquals(currentThread.getName(), event.threadName());
    }
}
