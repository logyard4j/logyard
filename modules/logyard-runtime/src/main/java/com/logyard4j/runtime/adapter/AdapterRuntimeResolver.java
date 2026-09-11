package com.logyard4j.runtime.adapter;

import com.logyard4j.runtime.bootstrap.ConfigurationDiscovery;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.bootstrap.RuntimeOwner;

import java.util.Objects;

/** Process-wide runtime resolver shared by every logging façade adapter. */
public final class AdapterRuntimeResolver {
    private AdapterRuntimeResolver() {
    }

    /** Borrows an application runtime, or leases exactly one adapter-owned runtime. */
    public static AdapterRuntimeHandle resolve(String adapterName) {
        requireName(adapterName);
        RuntimeBundle bundle = LogyardBootstrap.acquire(RuntimeOwner.ADAPTER, ConfigurationDiscovery.resolve());
        return new AdapterRuntimeHandle(bundle);
    }

    private static String requireName(String value) {
        String normalized = Objects.requireNonNull(value, "adapterName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
        return normalized;
    }
}
