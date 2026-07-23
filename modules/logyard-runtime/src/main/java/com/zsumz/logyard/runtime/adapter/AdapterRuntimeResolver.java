package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

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
