package com.zsumz.logyard.api.spi.encoding;

import com.zsumz.logyard.api.event.LogEvent;

/**
 * Converts one event to one framed text record without a trailing line break.
 *
 * <p>Logyard rejects {@code null}, embedded CR/LF framing, invalid media types, and
 * records larger than the runtime encoder bound. An encoder instance belongs to
 * one output in one immutable runtime plan and should not retain application
 * objects beyond an invocation.</p>
 */
@FunctionalInterface
public interface EventEncoder {
    /**
     * Encodes one event.
     *
     * @param event event to encode
     * @return one bounded record without a trailing line break
     */
    String encode(LogEvent event);

    /**
     * Returns the media type produced by this encoder.
     *
     * @return valid media type
     */
    default String mediaType() {
        return "text/plain; charset=utf-8";
    }
}
