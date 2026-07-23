package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.runtime.reload.ConfigurationWatcher;
import com.zsumz.logyard.runtime.reload.ReloadCoordinator;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one runtime, its current configuration, and optional reload infrastructure. */
public final class RuntimeBundle implements AutoCloseable {
    private final Path source;
    private final LogyardRuntime runtime;
    private final ReloadCoordinator reloadCoordinator;
    private final ConfigurationWatcher watcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    RuntimeBundle(Path source, LogyardRuntime runtime) {
        this(source, runtime, null, null);
    }

    RuntimeBundle(
            Path source,
            LogyardRuntime runtime,
            ReloadCoordinator reloadCoordinator,
            ConfigurationWatcher watcher) {
        this.source = source == null ? null : source.toAbsolutePath().normalize();
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.reloadCoordinator = reloadCoordinator;
        this.watcher = watcher;
    }

    public Path source() {
        return source;
    }

    public LogyardRuntime runtime() {
        return runtime;
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
