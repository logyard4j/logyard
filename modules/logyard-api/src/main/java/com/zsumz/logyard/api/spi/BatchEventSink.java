package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.LogEvent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Output optimized for bounded batches assembled by Logyard's asynchronous delivery worker. */
public interface BatchEventSink extends EventSink {
    /**
     * Returns the maximum number of events accepted in one invocation.
     *
     * @return positive batch-size limit
     */
    int maximumBatchSize();

    /**
     * Returns the maximum time the worker may wait to fill a non-empty batch.
     *
     * @return non-negative batch delay
     */
    Duration maximumBatchDelay();

    /**
     * Delivers one immutable, non-empty batch.
     *
     * @param events events to deliver in publication order
     */
    void acceptBatch(List<LogEvent> events);

    /**
     * Delivers one event as a singleton batch.
     *
     * @param event event to deliver
     */
    @Override
    default void accept(LogEvent event) {
        acceptBatch(List.of(Objects.requireNonNull(event, "event")));
    }
}
