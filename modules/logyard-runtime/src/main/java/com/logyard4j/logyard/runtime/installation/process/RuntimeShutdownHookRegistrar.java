package com.logyard4j.logyard.runtime.installation.process;

/** Registers the single process-exit callback without owning lifecycle state. */
@FunctionalInterface
public interface RuntimeShutdownHookRegistrar {
    boolean install(Runnable shutdown);
}
