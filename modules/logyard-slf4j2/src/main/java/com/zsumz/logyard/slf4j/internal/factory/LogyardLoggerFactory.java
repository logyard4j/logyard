package com.zsumz.logyard.slf4j.internal.factory;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.zsumz.logyard.slf4j.internal.logger.LogyardSlf4jLogger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/** Concurrent identity-preserving logger registry. */
public final class LogyardLoggerFactory implements ILoggerFactory {
    private final LogyardRuntime runtime;
    private final Slf4jEventMapper mapper;
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    public LogyardLoggerFactory(LogyardRuntime runtime, Slf4jEventMapper mapper) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
                key -> new LogyardSlf4jLogger(runtime.logger(key), mapper));
    }

    public int cachedLoggerCount() {
        return loggers.size();
    }
}
