package com.zsumz.logyard.runtime.extension;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.util.Objects;

/** Runtime-enforced size and framing boundaries around third-party formatter and encoder code. */
public final class ExtensionGuardrails {
    public static final int MAX_ENCODED_UTF8_BYTES = 1_048_576;
    public static final int MAX_MEDIA_TYPE_CHARS = 128;

    private ExtensionGuardrails() {
    }

    public static TextFormatter formatter(TextFormatter delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return event -> oneLine(CaptureLimits.text(Objects.requireNonNull(
                delegate.format(event), "text formatter returned null")));
    }

    /** Prevents a provider declared as an enricher from silently acting as a dropping filter. */
    public static EventProcessor enricher(String name, EventProcessor delegate) {
        String label = Objects.requireNonNull(name, "name").trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("enricher name must not be blank");
        }
        Objects.requireNonNull(delegate, "delegate");
        return event -> {
            com.zsumz.logyard.api.event.LogEvent enriched = delegate.process(
                    Objects.requireNonNull(event, "event"));
            if (enriched == null) {
                throw new IllegalStateException(
                        "enricher '" + label + "' attempted to drop an event");
            }
            return enriched;
        };
    }

    public static EventEncoder encoder(EventEncoder delegate) {
        Objects.requireNonNull(delegate, "delegate");
        String mediaType = Objects.requireNonNull(delegate.mediaType(), "encoder media type").trim();
        if (mediaType.isEmpty() || mediaType.length() > MAX_MEDIA_TYPE_CHARS || containsControl(mediaType)) {
            throw new IllegalArgumentException(
                    "event encoder media type must be 1 to " + MAX_MEDIA_TYPE_CHARS
                            + " characters without controls");
        }
        return new EventEncoder() {
            @Override
            public String encode(com.zsumz.logyard.api.event.LogEvent event) {
                String encoded = Objects.requireNonNull(
                        delegate.encode(event), "event encoder returned null");
                if (encoded.indexOf('\r') >= 0 || encoded.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException(
                            "event encoder must return exactly one record without line breaks");
                }
                if (utf8Length(encoded) > MAX_ENCODED_UTF8_BYTES) {
                    throw new IllegalArgumentException(
                            "event encoder record exceeds " + MAX_ENCODED_UTF8_BYTES + " UTF-8 bytes");
                }
                return encoded;
            }

            @Override
            public String mediaType() {
                return mediaType;
            }
        };
    }

    private static String oneLine(String value) {
        StringBuilder result = null;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            String replacement = switch (character) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                default -> Character.isISOControl(character)
                        ? String.format(java.util.Locale.ROOT, "\\u%04x", (int) character)
                        : null;
            };
            if (replacement != null) {
                if (result == null) {
                    result = new StringBuilder(value.length() + 8).append(value, 0, index);
                }
                result.append(replacement);
            } else if (result != null) {
                result.append(character);
            }
        }
        return result == null ? value : CaptureLimits.text(result.toString());
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static int utf8Length(String value) {
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_ENCODED_UTF8_BYTES) {
                return MAX_ENCODED_UTF8_BYTES + 1;
            }
        }
        return (int) bytes;
    }
}
