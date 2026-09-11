package com.logyard4j.runtime.assembly;

import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.api.annotation.InternalApi;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.core.failure.ComponentInvocationBoundary;
import com.logyard4j.core.runtime.RuntimePlan;
import com.logyard4j.runtime.assembly.output.ExclusiveOutputPathValidator;
import com.logyard4j.runtime.assembly.output.OutputBinding;
import com.logyard4j.runtime.assembly.output.OutputCandidateSet;
import com.logyard4j.runtime.assembly.output.OutputReuseSource;
import com.logyard4j.runtime.context.ContextPolicySnapshot;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fully validated candidate plan whose resources are either new or proven reusable. */
public final class RuntimeAssembly implements OutputReuseSource {
    private final LogyardConfig config;
    private final RuntimePlan plan;
    private final ContextPolicySnapshot contextPolicy;
    private final Map<String, OutputBinding> bindings;
    private final OutputCandidateSet candidates;

    RuntimeAssembly(LogyardConfig config, RuntimePlan plan, Map<String, OutputBinding> bindings, OutputCandidateSet candidates) {
        this.config = Objects.requireNonNull(config, "config");
        this.plan = Objects.requireNonNull(plan, "plan");
        contextPolicy = ContextPolicySnapshot.of(config.context().mdc());
        this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        this.candidates = Objects.requireNonNull(candidates, "candidates");
    }

    public LogyardConfig config() {
        return config;
    }

    public RuntimePlan plan() {
        return plan;
    }

    public ContextPolicySnapshot contextPolicy() {
        return contextPolicy;
    }

    @Override
    @InternalApi
    public OutputBinding binding(String outputName) {
        return bindings.get(outputName);
    }

    @Override
    @InternalApi
    public OutputBinding bindingForExclusivePath(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        for (OutputBinding binding : bindings.values()) {
            if (binding.exclusivePath() != null
                    && ExclusiveOutputPathValidator.refersToSameFile(normalized, binding.exclusivePath())) {
                return binding;
            }
        }
        return null;
    }

    /** Activates outputs only after the complete candidate has passed validation and ownership checks. */
    public void activateCandidateOutputs() {
        candidates.activate();
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
            ComponentInvocationBoundary.invoke(
                    "reload candidate output close",
                    sink::close,
                    (component, closeFailure) -> primaryFailure.addSuppressed(closeFailure));
        }
    }
}
