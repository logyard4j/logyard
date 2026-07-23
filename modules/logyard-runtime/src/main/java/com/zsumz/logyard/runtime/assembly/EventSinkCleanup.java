package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.EventSink;

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
            try {
                sink.close();
            } catch (RuntimeException closeFailure) {
                primaryFailure.addSuppressed(closeFailure);
            }
        }
    }
}
