package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.runtime.RuntimePlan;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully validated candidate plan whose resources are either new or proven reusable. */
public final class RuntimeAssembly {
    private final LogyardConfig config;
    private final RuntimePlan plan;
    private final Map<String, OutputBinding> bindings;

    RuntimeAssembly(LogyardConfig config, RuntimePlan plan, Map<String, OutputBinding> bindings) {
        this.config = Objects.requireNonNull(config, "config");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    public LogyardConfig config() {
        return config;
    }

    public RuntimePlan plan() {
        return plan;
    }

    public List<String> contextInclude() {
        return config.context().mdc();
    }

    OutputBinding binding(String outputName) {
        return bindings.get(outputName);
    }

    OutputBinding bindingForExclusivePath(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        for (OutputBinding binding : bindings.values()) {
            if (normalized.equals(binding.exclusivePath())) {
                return binding;
            }
        }
        return null;
    }

    /** Closes only candidate resources not shared with the currently published plan. */
    public void closeCandidateOutputs(RuntimeAssembly current, Throwable primaryFailure) {
        Set<EventSink> shared = Collections.newSetFromMap(new IdentityHashMap<>());
        if (current != null) {
            current.bindings.values().forEach(binding -> shared.add(binding.sink()));
        }
        Set<EventSink> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (OutputBinding binding : bindings.values()) {
            EventSink sink = binding.sink();
            if (shared.contains(sink) || !closed.add(sink)) {
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
