package com.logyard4j.api;

import com.logyard4j.api.diagnostics.EffectiveRoute;
import com.logyard4j.api.diagnostics.RuntimeHealth;

/** Minimal runtime port shared by native callers and compatibility adapters. */
public interface LogyardRuntime extends AutoCloseable {
    /**
     * Returns a logger named after a class.
     *
     * @param type class supplying the logger name
     * @return runtime-owned logger
     */
    LogyardLogger logger(Class<?> type);

    /**
     * Returns a logger with the supplied name.
     *
     * @param name logger name
     * @return runtime-owned logger
     */
    LogyardLogger logger(String name);

    /**
     * Explains the effective route for a logger name.
     *
     * @param loggerName logger name to resolve
     * @return immutable route description
     */
    EffectiveRoute explain(String loggerName);

    /**
     * Returns a point-in-time health snapshot for the runtime and its components.
     *
     * @return runtime health
     */
    RuntimeHealth health();

    /** Requests that every output deliver its currently buffered events. */
    void flush();

    /** Stops event delivery and releases all runtime-owned resources. */
    @Override
    void close();
}
