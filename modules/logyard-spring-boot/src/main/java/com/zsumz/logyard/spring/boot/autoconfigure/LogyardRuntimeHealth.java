package com.zsumz.logyard.spring.boot.autoconfigure;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import java.util.Objects;

/** Live, framework-neutral view of the installed runtime health snapshot. */
public final class LogyardRuntimeHealth {
    private final LogyardRuntime runtime;

    /** Creates a live view without taking runtime ownership. */
    public LogyardRuntimeHealth(LogyardRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Returns a fresh immutable health snapshot. */
    public RuntimeHealth snapshot() {
        return runtime.health();
    }
}
