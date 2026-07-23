package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

public interface EventSink extends AutoCloseable {
    void accept(LogEvent event);

    default void flush() {
    }

    @Override
    default void close() {
    }
}
