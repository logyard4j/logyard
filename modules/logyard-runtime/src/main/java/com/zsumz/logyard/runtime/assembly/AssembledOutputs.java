package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.output.EventSink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable result of a transactional output-assembly operation. */
record AssembledOutputs(Map<String, EventSink> sinks, Map<String, OutputBinding> bindings, List<EventSink> created) {
    AssembledOutputs {
        sinks = Collections.unmodifiableMap(new LinkedHashMap<>(sinks));
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        created = List.copyOf(created);
    }

    void closeCreated(Throwable primaryFailure) {
        EventSinkCleanup.close(created, primaryFailure);
    }
}
