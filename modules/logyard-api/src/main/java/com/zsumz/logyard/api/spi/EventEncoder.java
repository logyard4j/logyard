package com.zsumz.logyard.api.spi;

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
    String encode(LogEvent event);

    default String mediaType() {
        return "text/plain; charset=utf-8";
    }
}
