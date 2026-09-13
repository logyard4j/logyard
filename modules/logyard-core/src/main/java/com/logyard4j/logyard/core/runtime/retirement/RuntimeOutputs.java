package com.logyard4j.logyard.core.runtime.retirement;

import com.logyard4j.logyard.core.runtime.RuntimePlan;

import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.diagnostics.EmergencyText;
import com.logyard4j.logyard.core.diagnostics.EmergencyReporter;
import com.logyard4j.logyard.core.failure.ComponentFailureCollector;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;

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
        ComponentFailureCollector failures = new ComponentFailureCollector();
        List<EventSink> outputs = unique(plan);
        for (int index = 0; index < outputs.size(); index++) {
            EventSink sink = outputs.get(index);
            ComponentInvocationBoundary.invoke(
                    "runtime output " + index + " flush",
                    sink::flush,
                    failures);
        }
        failures.throwIfPresent("runtime output flush");
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
        ComponentInvocationBoundary.invoke(
                "runtime output close",
                sink::close,
                (component, failure) -> EmergencyReporter.STDERR.report(
                        "Logyard failed to close output: " + EmergencyText.failureSummary(failure, 4_096)));
    }
}
