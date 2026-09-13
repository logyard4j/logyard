package com.logyard4j.logyard.runtime.extension;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.logyard.api.spi.processing.EventProcessor;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;

import java.util.Objects;

/** Runtime-enforced size and framing boundaries around third-party formatter and encoder code. */
public final class ExtensionGuardrails {
    public static final int MAX_ENCODED_UTF8_BYTES = EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES;
    public static final int MAX_MEDIA_TYPE_CHARS = EventEncoderBoundary.MAX_MEDIA_TYPE_CHARACTERS;

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
            com.logyard4j.logyard.api.event.LogEvent enriched = delegate.process(
                    Objects.requireNonNull(event, "event"));
            if (enriched == null) {
                throw new IllegalStateException(
                        "enricher '" + label + "' attempted to drop an event");
            }
            return enriched;
        };
    }

    public static EventEncoder encoder(EventEncoder delegate) {
        return EventEncoderBoundary.guard(delegate);
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

}
