package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Lazy, thread-safe adapter access that performs no configuration work at construction time. */
public final class LazyAdapterRuntime implements AdapterRuntimeAccess {
    private final String adapterName;
    private final AtomicReference<AdapterRuntimeHandle> handle = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LazyAdapterRuntime(String adapterName) {
        this.adapterName = requireName(adapterName);
    }

    @Override
    public LogyardRuntime runtime() {
        return handle().runtime();
    }

    @Override
    public boolean initialized() {
        AdapterRuntimeHandle current = handle.get();
        return !closed.get() && current != null && current.initialized();
    }

    /** Returns whether the currently resolved adapter lease participates in managed ownership. */
    public boolean ownsRuntime() {
        AdapterRuntimeHandle current = handle.get();
        return !closed.get() && current != null && current.initialized() && current.ownsRuntime();
    }

    /** Returns a lazy context-policy source that follows whichever runtime is currently resolved. */
    public Supplier<ContextPolicySnapshot> contextPolicySource() {
        return () -> handle().contextPolicySource().get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AdapterRuntimeHandle current = handle.getAndSet(null);
        if (current != null) {
            current.close();
        }
    }

    private AdapterRuntimeHandle handle() {
        while (true) {
            if (closed.get()) {
                throw new IllegalStateException("Logyard adapter runtime access is closed");
            }
            AdapterRuntimeHandle current = handle.get();
            if (current != null && current.initialized()) {
                return current;
            }
            if (current != null) {
                if (handle.compareAndSet(current, null)) {
                    current.close();
                }
                continue;
            }

            AdapterRuntimeHandle candidate = AdapterRuntimeResolver.resolve(adapterName);
            if (closed.get()) {
                candidate.close();
                throw new IllegalStateException("Logyard adapter runtime access is closed");
            }
            if (handle.compareAndSet(null, candidate)) {
                return candidate;
            }
            candidate.close();
        }
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "adapterName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
        return normalized;
    }
}
