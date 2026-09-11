package com.logyard4j.api.spi.encoding;

import com.logyard4j.api.annotation.InternalApi;
import com.logyard4j.api.event.LogEvent;

import java.util.Objects;

/**
 * Applies Logyard's bounded, one-record framing contract to an event encoder.
 *
 * <p>This is shared implementation policy, not a supported extension point.</p>
 *
 * @hidden
 */
@InternalApi
public final class EventEncoderBoundary {
    /** Maximum UTF-8 bytes allowed in one encoded event record. */
    public static final int MAX_ENCODED_UTF8_BYTES = 1_048_576;

    /** Maximum characters accepted in the supplied encoder media type. */
    public static final int MAX_MEDIA_TYPE_CHARACTERS = 128;

    private EventEncoderBoundary() {
    }

    /**
     * Applies the encoder contract once.
     *
     * @param encoder encoder to guard
     * @return the existing guarded encoder or a guarded wrapper
     */
    public static EventEncoder guard(EventEncoder encoder) {
        Objects.requireNonNull(encoder, "encoder");
        if (encoder instanceof GuardedEventEncoder) {
            return encoder;
        }
        return new GuardedEventEncoder(encoder, validMediaType(encoder.mediaType()));
    }

    private static String validMediaType(String candidate) {
        String supplied = Objects.requireNonNull(candidate, "encoder media type");
        if (supplied.length() > MAX_MEDIA_TYPE_CHARACTERS) {
            throw invalidMediaType();
        }
        String mediaType = supplied.trim();
        if (mediaType.isEmpty() || containsControl(supplied)) {
            throw invalidMediaType();
        }
        return mediaType;
    }

    private static IllegalArgumentException invalidMediaType() {
        return new IllegalArgumentException(
                "event encoder media type must be 1 to " + MAX_MEDIA_TYPE_CHARACTERS + " characters without controls");
    }

    private static String validRecord(String candidate) {
        String record = Objects.requireNonNull(candidate, "event encoder returned null");
        if (record.length() > MAX_ENCODED_UTF8_BYTES) {
            throw oversizedRecord();
        }
        if (record.indexOf('\r') >= 0 || record.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("event encoder must return exactly one record without line breaks");
        }
        if (utf8LengthExceedsBoundary(record)) {
            throw oversizedRecord();
        }
        return record;
    }

    private static IllegalArgumentException oversizedRecord() {
        return new IllegalArgumentException(
                "event encoder record exceeds " + MAX_ENCODED_UTF8_BYTES + " UTF-8 bytes");
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean utf8LengthExceedsBoundary(String value) {
        int remaining = MAX_ENCODED_UTF8_BYTES;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            int bytes;
            if (character <= 0x7f) {
                bytes = 1;
            } else if (character <= 0x7ff) {
                bytes = 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes = 4;
                index++;
            } else {
                bytes = 3;
            }
            remaining -= bytes;
            if (remaining < 0) {
                return true;
            }
        }
        return false;
    }

    private record GuardedEventEncoder(EventEncoder delegate, String mediaType) implements EventEncoder {
        @Override
        public String encode(LogEvent event) {
            return validRecord(delegate.encode(event));
        }
    }
}
