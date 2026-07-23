package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

import java.util.List;

interface AdapterRuntimeLease {
    LogyardRuntime runtime();

    List<String> contextInclude();

    boolean active();

    boolean ownsRuntime();

    void release();
}
