package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One ownership-aware lease on the runtime selected for a compatibility adapter. */
public final class AdapterRuntimeHandle implements AdapterRuntimeAccess {
    private final LogyardRuntime runtime;
    private final AdapterRuntimeResolver.SharedOwned sharedOwned;
    private final AtomicBoolean closed = new AtomicBoolean();

    AdapterRuntimeHandle(LogyardRuntime runtime, AdapterRuntimeResolver.SharedOwned sharedOwned) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.sharedOwned = sharedOwned;
    }

    @Override
    public LogyardRuntime runtime() {
        if (!initialized()) {
            throw new IllegalStateException("Logyard adapter runtime lease is closed or stale");
        }
        return runtime;
    }

    /** Immutable context keys from the currently published configuration. */
    @Override
    public List<String> contextInclude() {
        if (!initialized()) {
            return List.of();
        }
        return sharedOwned == null
                ? AdapterRuntimeResolver.contextInclude(runtime)
                : sharedOwned.contextInclude();
    }

    public boolean ownsRuntime() {
        return sharedOwned != null;
    }

    @Override
    public boolean initialized() {
        return !closed.get() && (sharedOwned == null
                ? Logyard.runtimeOrNull() == runtime
                : sharedOwned.active(runtime));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || sharedOwned == null) {
            return;
        }
        AdapterRuntimeResolver.release(sharedOwned);
    }
}
