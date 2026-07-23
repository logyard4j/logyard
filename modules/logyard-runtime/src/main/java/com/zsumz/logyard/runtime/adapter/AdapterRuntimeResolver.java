package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.Objects;

/** Process-wide runtime resolver shared by every logging façade adapter. */
public final class AdapterRuntimeResolver {
    private static final AdapterRuntimeCoordinator COORDINATOR = new AdapterRuntimeCoordinator();

    private AdapterRuntimeResolver() {
    }

    /** Borrows an application runtime, or leases exactly one adapter-owned runtime. */
    public static AdapterRuntimeHandle resolve(String adapterName) {
        return COORDINATOR.resolve(requireName(adapterName));
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "adapterName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
        return normalized;
    }
}
