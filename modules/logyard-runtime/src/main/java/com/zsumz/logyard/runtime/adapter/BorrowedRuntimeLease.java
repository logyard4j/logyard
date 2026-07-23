package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.Objects;
import java.util.function.Supplier;

final class BorrowedRuntimeLease implements AdapterRuntimeLease {
    private final LogyardRuntime runtime;
    private final Supplier<LogyardRuntime> currentRuntime;

    BorrowedRuntimeLease(LogyardRuntime runtime, Supplier<LogyardRuntime> currentRuntime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.currentRuntime = Objects.requireNonNull(currentRuntime, "currentRuntime");
    }

    @Override
    public LogyardRuntime runtime() {
        return runtime;
    }

    @Override
    public boolean active() {
        return currentRuntime.get() == runtime;
    }

    @Override
    public boolean ownsRuntime() {
        return false;
    }

    @Override
    public void release() {
        // Application ownership is never transferred to an adapter.
    }
}
