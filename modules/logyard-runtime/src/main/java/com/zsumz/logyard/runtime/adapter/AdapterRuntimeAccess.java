package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.List;

/** Runtime access strategy shared by lazy service-loaded and application-owned adapters. */
public interface AdapterRuntimeAccess extends AutoCloseable {
    LogyardRuntime runtime();

    List<String> contextInclude();

    boolean initialized();

    @Override
    void close();
}
