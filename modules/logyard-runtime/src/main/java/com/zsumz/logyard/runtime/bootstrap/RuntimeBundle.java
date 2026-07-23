package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;
import com.zsumz.logyard.runtime.reload.ReloadResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one runtime, its current configuration, and optional reload infrastructure. */
public final class RuntimeBundle implements AutoCloseable {
    private final Path source;
    private final LogyardConfig initialConfig;
    private final LogyardRuntime runtime;
    private final ReloadCoordinator reloadCoordinator;
    private final ConfigurationWatcher watcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    public RuntimeBundle(Path source, LogyardConfig config, LogyardRuntime runtime) {
        this(source, config, runtime, null, null);
    }

    RuntimeBundle(
            Path source,
            LogyardConfig config,
            LogyardRuntime runtime,
            ReloadCoordinator reloadCoordinator,
            ConfigurationWatcher watcher) {
        this.source = source == null ? null : source.toAbsolutePath().normalize();
        initialConfig = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.reloadCoordinator = reloadCoordinator;
        this.watcher = watcher;
    }

    public Path source() {
        return source;
    }

    public LogyardConfig config() {
        return reloadCoordinator == null ? initialConfig : reloadCoordinator.currentConfig();
    }

    public LogyardRuntime runtime() {
        return runtime;
    }

    /** Immutable context keys adapters may capture from thread-local façade state. */
    public List<String> contextInclude() {
        return config().context().mdc();
    }

    public boolean watchesConfiguration() {
        return watcher != null;
    }

    public ReloadResult reloadNow() {
        if (reloadCoordinator == null) {
            throw new IllegalStateException("this runtime bundle has no configuration source");
        }
        return reloadCoordinator.reloadIfChanged();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (RuntimeException watcherFailure) {
                failure = watcherFailure;
            }
        }
        try {
            if (Logyard.runtimeOrNull() == runtime) {
                Logyard.shutdown();
            } else {
                runtime.close();
            }
        } catch (RuntimeException runtimeFailure) {
            if (failure == null) {
                failure = runtimeFailure;
            } else {
                failure.addSuppressed(runtimeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
