package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

/** Runtime access strategy shared by lazy service-loaded and application-owned adapters. */
public interface AdapterRuntimeAccess extends AutoCloseable {
    LogyardRuntime runtime();

    boolean initialized();

    @Override
    void close();
}
