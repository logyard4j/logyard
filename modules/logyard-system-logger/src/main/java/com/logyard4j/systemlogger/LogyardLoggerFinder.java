package com.logyard4j.systemlogger;

import com.logyard4j.runtime.adapter.LazyAdapterRuntime;
import com.logyard4j.systemlogger.internal.factory.LogyardSystemLoggerRegistry;

import java.util.Objects;

/** Service-loaded System.LoggerFinder with intentionally lightweight construction. */
public final class LogyardLoggerFinder extends System.LoggerFinder {
    private final LogyardSystemLoggerRegistry registry =
            new LogyardSystemLoggerRegistry(new LazyAdapterRuntime("system-logger"));

    public LogyardLoggerFinder() {
        // The JDK may construct LoggerFinder during early bootstrap. Do not read configuration here.
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return registry.logger(
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(module, "module"));
    }
}
