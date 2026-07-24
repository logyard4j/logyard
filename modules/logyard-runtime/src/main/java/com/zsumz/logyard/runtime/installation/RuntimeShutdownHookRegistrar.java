package com.zsumz.logyard.runtime.installation;

@FunctionalInterface
interface RuntimeShutdownHookRegistrar {
    boolean install(Runnable shutdown);
}
