package com.logyard4j.logyard.slf4j.internal.factory;

import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.runtime.adapter.AdapterRuntimeAccess;
import com.logyard4j.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.logyard4j.logyard.slf4j.internal.logger.LogyardSlf4jLogger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Concurrent identity-preserving logger registry. */
public final class LogyardLoggerFactory implements ILoggerFactory {
    private final Function<String, com.logyard4j.logyard.api.LogyardLogger> loggerResolver;
    private final Slf4jEventMapper mapper;
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    public LogyardLoggerFactory(LogyardRuntime runtime, Slf4jEventMapper mapper) {
        LogyardRuntime resolved = Objects.requireNonNull(runtime, "runtime");
        loggerResolver = resolved::logger;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public LogyardLoggerFactory(AdapterRuntimeAccess runtime, Slf4jEventMapper mapper) {
        AdapterRuntimeAccess resolved = Objects.requireNonNull(runtime, "runtime");
        loggerResolver = name -> resolved.runtime().logger(name);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Logger getLogger(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("SLF4J logger name must not be blank");
        }
        if (name.length() > CaptureLimits.MAX_NAME_CHARS) {
            throw new IllegalArgumentException(
                    "SLF4J logger name exceeds " + CaptureLimits.MAX_NAME_CHARS + " characters");
        }
        return loggers.computeIfAbsent(
                name,
                key -> new LogyardSlf4jLogger(key, () -> loggerResolver.apply(key), mapper));
    }

    public int cachedLoggerCount() {
        return loggers.size();
    }
}
