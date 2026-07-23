package com.zsumz.logyard.runtime.installation;

@FunctionalInterface
interface RuntimeShutdownHookRegistrar {
    void install(Runnable shutdown);
}
