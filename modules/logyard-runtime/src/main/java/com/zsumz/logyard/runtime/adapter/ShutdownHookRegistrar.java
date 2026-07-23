package com.zsumz.logyard.runtime.adapter;

@FunctionalInterface
interface ShutdownHookRegistrar {
    void install(String adapterName, Runnable shutdown);
}
