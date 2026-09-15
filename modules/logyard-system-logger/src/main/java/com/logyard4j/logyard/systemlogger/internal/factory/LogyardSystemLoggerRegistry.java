package com.logyard4j.logyard.systemlogger.internal.factory;

import com.logyard4j.logyard.runtime.adapter.AdapterRuntimeAccess;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Cache preserving System.Logger identity by logger name and module. */
public final class LogyardSystemLoggerRegistry {
    private final AdapterRuntimeAccess runtime;
    private final ConcurrentHashMap<Key, System.Logger> loggers = new ConcurrentHashMap<>();

    public LogyardSystemLoggerRegistry(AdapterRuntimeAccess runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public System.Logger logger(String name, Module module) {
        String normalizedName = LogyardSystemLogger.requireName(name);
        Objects.requireNonNull(module, "module");
        return loggers.computeIfAbsent(new Key(normalizedName, module),
                key -> new LogyardSystemLogger(key.name(), key.module(), runtime));
    }

    public int size() {
        return loggers.size();
    }

    private record Key(String name, Module module) {
    }
}
