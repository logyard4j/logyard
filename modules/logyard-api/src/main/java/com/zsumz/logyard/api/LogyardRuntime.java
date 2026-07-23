package com.zsumz.logyard.api;

import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;

/** Minimal runtime port shared by native callers and compatibility adapters. */
public interface LogyardRuntime extends AutoCloseable {
    LogyardLogger logger(Class<?> type);

    LogyardLogger logger(String name);

    EffectiveRoute explain(String loggerName);

    RuntimeHealth health();

    void flush();

    @Override
    void close();
}
