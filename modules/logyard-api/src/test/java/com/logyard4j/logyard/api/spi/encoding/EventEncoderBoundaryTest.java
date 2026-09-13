package com.logyard4j.logyard.api.spi.encoding;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventEncoderBoundaryTest {
    private static final LogEvent EVENT = new LogEvent(
            0L, 0L, Level.INFO, "test.Logger", "test", "message", null, AttributeSet.EMPTY, null, 1L, "test");

    @Test
    void rejectsNullAndMultiRecordResults() {
        assertRejected(event -> null, NullPointerException.class);
        assertRejected(event -> "first\nsecond", IllegalArgumentException.class);
        assertRejected(event -> "first\rsecond", IllegalArgumentException.class);
    }

    @Test
    void enforcesTheUtf8BoundaryWithoutAllocatingEncodedBytes() {
        EventEncoder exactAscii = EventEncoderBoundary.guard(
                event -> "a".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES));
        assertEquals(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES, exactAscii.encode(EVENT).length());
        EventEncoder exactMultibyte = EventEncoderBoundary.guard(
                event -> "\u00e9".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES / 2));
        assertEquals(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES / 2, exactMultibyte.encode(EVENT).length());

        assertRejected(
                event -> "a".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES + 1),
                IllegalArgumentException.class);
        assertRejected(
                event -> "\u00e9".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES / 2 + 1),
                IllegalArgumentException.class);
    }

    @Test
    void snapshotsAndValidatesMediaTypeOnce() {
        AtomicInteger calls = new AtomicInteger();
        EventEncoder encoder = new EventEncoder() {
            @Override
            public String encode(LogEvent event) {
                return "{}";
            }

            @Override
            public String mediaType() {
                calls.incrementAndGet();
                return " application/json ";
            }
        };

        EventEncoder guarded = EventEncoderBoundary.guard(encoder);

        assertEquals("application/json", guarded.mediaType());
        assertEquals("{}", guarded.encode(EVENT));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidMediaTypesDuringGuardConstruction() {
        assertMediaTypeRejected(null, NullPointerException.class);
        assertMediaTypeRejected("  ", IllegalArgumentException.class);
        assertMediaTypeRejected("x".repeat(EventEncoderBoundary.MAX_MEDIA_TYPE_CHARACTERS + 1), IllegalArgumentException.class);
        assertMediaTypeRejected(
                "application/json" + " ".repeat(EventEncoderBoundary.MAX_MEDIA_TYPE_CHARACTERS),
                IllegalArgumentException.class);
        assertMediaTypeRejected("application/json\u0007", IllegalArgumentException.class);
    }

    @Test
    void guardingIsIdempotent() {
        EventEncoder guarded = EventEncoderBoundary.guard(event -> "{}");
        assertSame(guarded, EventEncoderBoundary.guard(guarded));
    }

    @Test
    void rejectsImpossibleCharacterCountsBeforeFramingAndUtf8Scans() {
        EventEncoder guarded = EventEncoderBoundary.guard(
                event -> "a".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES) + '\n');

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> guarded.encode(EVENT));

        assertTrue(failure.getMessage().contains("UTF-8 bytes"));
    }

    private static void assertRejected(EventEncoder encoder, Class<? extends Throwable> failureType) {
        EventEncoder guarded = EventEncoderBoundary.guard(encoder);
        assertThrows(failureType, () -> guarded.encode(EVENT));
    }

    private static void assertMediaTypeRejected(String mediaType, Class<? extends Throwable> failureType) {
        EventEncoder encoder = new EventEncoder() {
            @Override
            public String encode(LogEvent event) {
                return "{}";
            }

            @Override
            public String mediaType() {
                return mediaType;
            }
        };
        assertThrows(failureType, () -> EventEncoderBoundary.guard(encoder));
    }
}
