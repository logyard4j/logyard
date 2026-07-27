package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.api.spi.output.EventSink;

import java.util.ArrayList;
import java.util.List;

/** Owns newly prepared outputs until they are activated or abandoned. */
public final class OutputCandidateSet {
    private final List<OutputPreparation> preparations;
    private boolean activated;

    OutputCandidateSet(List<OutputPreparation> preparations) {
        this.preparations = List.copyOf(preparations);
    }

    public void activate() {
        if (activated) {
            return;
        }
        for (OutputPreparation preparation : preparations) {
            preparation.activate();
        }
        activated = true;
    }

    void close(Throwable failure) {
        List<EventSink> sinks = new ArrayList<>(preparations.size());
        preparations.forEach(preparation -> sinks.add(preparation.sink()));
        EventSinkCleanup.close(sinks, failure);
    }
}
