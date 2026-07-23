package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Identity-aware operations over the outputs owned by immutable runtime plans. */
final class RuntimeOutputs {
    private RuntimeOutputs() {
    }

    static void flush(RuntimePlan plan) {
        for (EventSink sink : unique(plan)) {
            sink.flush();
        }
    }

    static void closeNotReused(RuntimePlan previous, RuntimePlan next) {
        Set<EventSink> reused = identitySet();
        reused.addAll(next.outputs().values());
        for (EventSink sink : unique(previous)) {
            if (!reused.contains(sink)) {
                closeQuietly(sink);
            }
        }
    }

    static void closeAll(RuntimePlan plan) {
        for (EventSink sink : unique(plan)) {
            closeQuietly(sink);
        }
    }

    static List<EventSink> unique(RuntimePlan plan) {
        Set<EventSink> seen = identitySet();
        List<EventSink> result = new ArrayList<>();
        for (EventSink sink : plan.outputs().values()) {
            if (seen.add(sink)) {
                result.add(sink);
            }
        }
        return result;
    }

    private static Set<EventSink> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void closeQuietly(EventSink sink) {
        try {
            sink.close();
        } catch (RuntimeException failure) {
            System.err.println("Logyard failed to close output: " + EmergencyText.failureSummary(failure, 4_096));
        }
    }
}
