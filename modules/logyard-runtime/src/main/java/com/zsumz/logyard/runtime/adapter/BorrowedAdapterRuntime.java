package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.List;
import java.util.Objects;

/** Non-owning adapter access for dependency injection and application-managed runtimes. */
public final class BorrowedAdapterRuntime implements AdapterRuntimeAccess {
    private final LogyardRuntime runtime;

    public BorrowedAdapterRuntime(LogyardRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public LogyardRuntime runtime() {
        return runtime;
    }

    @Override
    public List<String> contextInclude() {
        return AdapterRuntimeResolver.contextInclude(runtime);
    }

    @Override
    public boolean initialized() {
        return true;
    }

    @Override
    public void close() {
        // Application ownership is never transferred to an adapter.
    }
}
