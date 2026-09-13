package com.logyard4j.logyard.runtime.assembly.output;

import com.logyard4j.logyard.api.spi.output.EventSink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable result of a transactional output-assembly operation. */
public record AssembledOutputs(Map<String, EventSink> sinks, Map<String, OutputBinding> bindings, OutputCandidateSet candidates) {
    public AssembledOutputs {
        sinks = Collections.unmodifiableMap(new LinkedHashMap<>(sinks));
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    public void closeCreated(Throwable primaryFailure) {
        candidates.close(primaryFailure);
    }
}
