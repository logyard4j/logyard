package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.api.LogyardRuntime;

interface AdapterRuntimeLease {
    LogyardRuntime runtime();

    boolean active();

    boolean ownsRuntime();

    void release();
}
