package com.logyard4j.logyard.runtime.assembly.output;

import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Identity-aware rollback cleanup for output resources created by a failed assembly. */
final class EventSinkCleanup {
    private EventSinkCleanup() {
    }

    static void close(List<EventSink> sinks, Throwable primaryFailure) {
        Objects.requireNonNull(sinks, "sinks");
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        Set<EventSink> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EventSink sink : sinks) {
            if (!closed.add(sink)) {
                continue;
            }
            ComponentInvocationBoundary.invoke(
                    "candidate output rollback close",
                    sink::close,
                    (component, closeFailure) -> primaryFailure.addSuppressed(closeFailure));
        }
    }
}
