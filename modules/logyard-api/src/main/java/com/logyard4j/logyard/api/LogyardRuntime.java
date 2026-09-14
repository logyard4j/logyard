package com.logyard4j.logyard.api;

import com.logyard4j.logyard.api.diagnostics.EffectiveRoute;
import com.logyard4j.logyard.api.diagnostics.RuntimeHealth;
import com.logyard4j.logyard.api.event.CaptureLimits;

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
     * @param name logger name, from 1 to {@value CaptureLimits#MAX_NAME_CHARS} characters
     * @return runtime-owned logger
     * @throws NullPointerException if {@code name} is null
     * @throws IllegalArgumentException if {@code name} is blank or exceeds the name limit
     */
    LogyardLogger logger(String name);

    /**
     * Explains the effective route and operational level enablement for a logger name.
     *
     * @param loggerName logger name to resolve, from 1 to
     *                   {@value CaptureLimits#MAX_NAME_CHARS} characters
     * @return immutable route description
     * @throws NullPointerException if {@code loggerName} is null
     * @throws IllegalArgumentException if {@code loggerName} is blank or exceeds the name limit
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
