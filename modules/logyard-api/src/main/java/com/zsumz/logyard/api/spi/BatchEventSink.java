package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Output optimized for bounded batches assembled by Logyard's asynchronous delivery worker. */
public interface BatchEventSink extends EventSink {
    /** Maximum number of events accepted in one invocation. */
    int maximumBatchSize();

    /** Maximum time the worker may wait to fill a non-empty batch. */
    Duration maximumBatchDelay();

    /** Delivers one immutable, non-empty batch. */
    void acceptBatch(List<LogEvent> events);

    @Override
    default void accept(LogEvent event) {
        acceptBatch(List.of(Objects.requireNonNull(event, "event")));
    }
}
