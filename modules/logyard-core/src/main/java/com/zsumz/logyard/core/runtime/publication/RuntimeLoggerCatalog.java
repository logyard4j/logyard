package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Owns one runtime's stable loggers and their replaceable compiled route controls. */
final class RuntimeLoggerCatalog {
    private final Object stateLock;
    private final Function<String, CompiledRoute> routeCompiler;
    private final RuntimePublication.EventPublisher publisher;
    private final ConcurrentHashMap<String, DefaultLogyardLogger> loggers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoggerControl> controls = new ConcurrentHashMap<>();

    RuntimeLoggerCatalog(
            Object stateLock,
            Function<String, CompiledRoute> routeCompiler,
            RuntimePublication.EventPublisher publisher) {
        this.stateLock = Objects.requireNonNull(stateLock, "stateLock");
        this.routeCompiler = Objects.requireNonNull(routeCompiler, "routeCompiler");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    LogyardLogger logger(String name) {
        return loggers.computeIfAbsent(name, this::createLogger);
    }

    int size() {
        return loggers.size();
    }

    Set<String> controlNames() {
        return controls.keySet();
    }

    void forEachControl(BiConsumer<String, LoggerControl> action) {
        controls.forEach(action);
    }

    void updateRoutes(Map<String, CompiledRoute> routes) {
        routes.forEach((name, route) -> controls.get(name).update(route));
    }

    private DefaultLogyardLogger createLogger(String name) {
        synchronized (stateLock) {
            LoggerControl control = new LoggerControl(routeCompiler.apply(name));
            controls.put(name, control);
            return new DefaultLogyardLogger(name, publisher, control);
        }
    }
}
