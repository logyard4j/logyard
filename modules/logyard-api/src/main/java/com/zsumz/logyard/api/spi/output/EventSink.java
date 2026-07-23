package com.zsumz.logyard.api.spi.output;

import com.zsumz.logyard.api.event.LogEvent;

/** Receives immutable events at an output boundary. */
public interface EventSink extends AutoCloseable {
    /**
     * Accepts one event.
     *
     * @param event event to deliver
     */
    void accept(LogEvent event);

    /** Delivers buffered events when the transport supports explicit flushing. */
    default void flush() {
    }

    /** Releases resources owned by this sink. */
    @Override
    default void close() {
    }
}
