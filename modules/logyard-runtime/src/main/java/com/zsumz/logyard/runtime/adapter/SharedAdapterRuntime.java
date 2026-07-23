package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;

import java.util.Objects;

final class SharedAdapterRuntime implements AdapterRuntimeLease {
    private final AdapterRuntimeCoordinator coordinator;
    private final RuntimeBundle bundle;
    private int leases;
    private volatile boolean closed;

    SharedAdapterRuntime(AdapterRuntimeCoordinator coordinator, RuntimeBundle bundle) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.bundle = Objects.requireNonNull(bundle, "bundle");
    }

    AdapterRuntimeHandle lease() {
        if (closed) {
            throw new IllegalStateException("Logyard adapter runtime is closing");
        }
        leases++;
        return new AdapterRuntimeHandle(this);
    }

    boolean releaseLease() {
        if (closed || leases == 0) {
            return false;
        }
        leases--;
        if (leases == 0) {
            closed = true;
            return true;
        }
        return false;
    }

    RuntimeBundle forceClose() {
        closed = true;
        leases = 0;
        return bundle;
    }

    boolean globallyActive() {
        return active();
    }

    @Override
    public LogyardRuntime runtime() {
        return bundle.runtime();
    }

    @Override
    public boolean active() {
        return !closed && coordinator.isCurrent(bundle.runtime());
    }

    @Override
    public boolean ownsRuntime() {
        return true;
    }

    @Override
    public void release() {
        coordinator.release(this);
    }
}
