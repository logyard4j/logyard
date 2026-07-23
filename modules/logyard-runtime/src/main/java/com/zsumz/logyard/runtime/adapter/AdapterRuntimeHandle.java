package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One ownership-aware lease on the runtime selected for a compatibility adapter. */
public final class AdapterRuntimeHandle implements AdapterRuntimeAccess {
    private final AdapterRuntimeLease lease;
    private final AtomicBoolean closed = new AtomicBoolean();

    AdapterRuntimeHandle(AdapterRuntimeLease lease) {
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    @Override
    public LogyardRuntime runtime() {
        if (!initialized()) {
            throw new IllegalStateException("Logyard adapter runtime lease is closed or stale");
        }
        return lease.runtime();
    }

    /** Immutable context keys from the currently published configuration. */
    @Override
    public List<String> contextInclude() {
        if (!initialized()) {
            return List.of();
        }
        return lease.contextInclude();
    }

    public boolean ownsRuntime() {
        return lease.ownsRuntime();
    }

    @Override
    public boolean initialized() {
        return !closed.get() && lease.active();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lease.release();
    }
}
