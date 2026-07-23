package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.context.ContextPolicyRegistry;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One ownership-aware lease on the runtime selected for a compatibility adapter. */
public final class AdapterRuntimeHandle implements AdapterRuntimeAccess {
    private final RuntimeBundle bundle;
    private final AtomicBoolean closed = new AtomicBoolean();

    AdapterRuntimeHandle(RuntimeBundle bundle) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    @Override
    public LogyardRuntime runtime() {
        if (!initialized()) {
            throw new IllegalStateException("Logyard adapter runtime lease is closed or stale");
        }
        return bundle.runtime();
    }

    public boolean ownsRuntime() {
        return bundle.ownsRuntime();
    }

    /** Stable context-policy source for event adapters; obtaining it is a control-plane operation. */
    public Supplier<ContextPolicySnapshot> contextPolicySource() {
        return ContextPolicyRegistry.sourceFor(runtime());
    }

    @Override
    public boolean initialized() {
        return !closed.get() && bundle.active();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        bundle.close();
    }
}
