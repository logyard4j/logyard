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
    private final LogyardConfigurationSource configurationSource;
    private final LogyardRuntime runtime;
    private final ReloadCoordinator reloadCoordinator;
    private final ConfigurationWatcher watcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    RuntimeBundle(
            LogyardConfigurationSource configurationSource,
            LogyardRuntime runtime,
            ReloadCoordinator reloadCoordinator,
            ConfigurationWatcher watcher) {
        this.configurationSource = Objects.requireNonNull(configurationSource, "configurationSource");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.reloadCoordinator = reloadCoordinator;
        this.watcher = watcher;
    }

    /**
     * Returns the watched filesystem path used by the legacy path bootstrap, or {@code null} for
     * classpath, text, default, and source-less bundles.
     *
     * @return watched filesystem configuration path, if any
     */
    public Path source() {
        return configurationSource == null ? null : configurationSource.watchPath();
    }

    /**
     * Returns the configuration source that owns this bundle.
     *
     * @return configuration source
     */
    public LogyardConfigurationSource configurationSource() {
        return configurationSource;
    }

    /**
     * Returns the owned runtime.
     *
     * @return Logyard runtime
     */
    public LogyardRuntime runtime() {
        return runtime;
    }

    /**
     * Reports whether a filesystem watcher is active.
     *
     * @return {@code true} when changes are watched
     */
    public boolean watchesConfiguration() {
        return watcher != null;
    }

    /**
     * Re-reads the source and applies changed content atomically.
     *
     * @return reload outcome
     */
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
