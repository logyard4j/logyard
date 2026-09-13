package com.logyard4j.logyard.runtime.adapter;

import com.logyard4j.logyard.api.LogyardRuntime;

/** Runtime access strategy shared by lazy service-loaded and application-owned adapters. */
public interface AdapterRuntimeAccess extends AutoCloseable {
    LogyardRuntime runtime();

    boolean initialized();

    @Override
    void close();
}
