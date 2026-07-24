package com.zsumz.logyard.api.spi.output;

import com.zsumz.logyard.api.event.LogEvent;

/**
 * Receives immutable events at an output boundary.
 *
 * <p>An implementation may render or encode an event before it enters the transport's serialized
 * write stage. Consequently, {@link #flush()} flushes bytes already handed to that transport; it is
 * not required to wait for concurrent {@link #accept(LogEvent)} calls still performing extension
 * computation. Runtime lifecycle draining supplies the stronger barrier before managed shutdown.</p>
 */
public interface EventSink extends AutoCloseable {
    /**
     * Accepts one event.
     *
     * @param event event to deliver
     */
    void accept(LogEvent event);

    /** Flushes bytes already handed to the transport when explicit flushing is supported. */
    default void flush() {
    }

    /** Releases resources owned by this sink. */
    @Override
    default void close() {
    }
}
