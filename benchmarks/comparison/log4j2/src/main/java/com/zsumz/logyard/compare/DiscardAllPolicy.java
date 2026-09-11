package com.zsumz.logyard.compare;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.async.AsyncQueueFullPolicy;
import org.apache.logging.log4j.core.async.EventRoute;

import java.util.concurrent.atomic.LongAdder;

/** Matches Logyard's latency-first DROP policy at every severity for the controlled comparison. */
public final class DiscardAllPolicy implements AsyncQueueFullPolicy {
    static final LongAdder dropped = new LongAdder();
    public DiscardAllPolicy() {
    }

    @Override
    public EventRoute getRoute(long backgroundThreadId, Level level) {
        dropped.increment();
        return EventRoute.DISCARD;
    }
}
