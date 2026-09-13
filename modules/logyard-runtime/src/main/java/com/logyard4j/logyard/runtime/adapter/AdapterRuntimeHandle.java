package com.logyard4j.logyard.runtime.adapter;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.lifecycle.CloseLifecycle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.context.ContextPolicyRegistry;
import com.logyard4j.logyard.runtime.context.ContextPolicySnapshot;

import java.util.Objects;
import java.util.function.Supplier;

/** One ownership-aware lease on the runtime selected for a compatibility adapter. */
public final class AdapterRuntimeHandle implements AdapterRuntimeAccess {
    private final RuntimeBundle bundle;
    private final LogyardRuntime runtime;
    private final Supplier<ContextPolicySnapshot> contextPolicySource;
    private final CloseLifecycle lifecycle = new CloseLifecycle();

    AdapterRuntimeHandle(RuntimeBundle bundle) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        runtime = bundle.runtime();
        contextPolicySource = ContextPolicyRegistry.sourceFor(runtime);
    }

    @Override
    public LogyardRuntime runtime() {
        if (!initialized()) {
            throw new IllegalStateException("Logyard adapter runtime lease is closed or stale");
        }
        return runtime;
    }

    public boolean ownsRuntime() {
        return bundle.ownsRuntime();
    }

    /** Stable context-policy source for event adapters; obtaining it is a control-plane operation. */
    public Supplier<ContextPolicySnapshot> contextPolicySource() {
        return contextPolicySource;
    }

    @Override
    public boolean initialized() {
        return lifecycle.open() && bundle.active();
    }

    @Override
    public void close() {
        if (!lifecycle.beginClose()) {
            return;
        }
        bundle.close();
    }
}
