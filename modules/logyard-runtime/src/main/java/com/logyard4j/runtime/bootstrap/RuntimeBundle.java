package com.logyard4j.runtime.bootstrap;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.installation.process.RuntimeInstallationLease;

import java.nio.file.Path;
import java.util.Objects;

/** One ownership lease on the process-wide runtime and its active configuration lifecycle. */
public final class RuntimeBundle implements AutoCloseable {
    private final LogyardConfigurationSource configurationSource;
    private final RuntimeInstallationLease lease;

    RuntimeBundle(
            LogyardConfigurationSource configurationSource,
            RuntimeInstallationLease lease) {
        this.configurationSource = Objects.requireNonNull(configurationSource, "configurationSource");
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    /**
     * Returns the filesystem path selected by this acquisition, or {@code null} for classpath,
     * text, and default sources.
     *
     * @return selected filesystem configuration path, if any
     */
    public Path source() {
        return configurationSource.watchPath();
    }

    /**
     * Returns the configuration source selected by this acquisition.
     *
     * @return configuration source
     */
    public LogyardConfigurationSource configurationSource() {
        return configurationSource;
    }

    /**
     * Returns the shared runtime. After the final lease closes, the returned runtime is stopped.
     *
     * @return shared Logyard runtime
     */
    public LogyardRuntime runtime() {
        return lease.runtime();
    }

    /**
     * Reports whether this lease still refers to the installed runtime.
     *
     * @return {@code true} while active
     */
    public boolean active() {
        return lease.active();
    }

    /**
     * Reports whether this lease participates in managed runtime ownership.
     *
     * @return {@code true} for manager-owned runtimes, or {@code false} for a borrowed external runtime
     */
    public boolean ownsRuntime() {
        return lease.ownsRuntime();
    }

    /**
     * Reports whether the shared runtime currently has an active filesystem watcher.
     *
     * @return {@code true} when changes are watched
     */
    public boolean watchesConfiguration() {
        return lease.watchesConfiguration();
    }

    /**
     * Re-reads the active process-wide source and applies changed content atomically.
     * The installation's captured profile and overrides remain fixed until a fresh installation.
     *
     * @return reload outcome
     */
    public ReloadResult reloadNow() {
        return lease.reloadNow();
    }

    /** Releases this ownership lease and closes the runtime only when it is the final lease. */
    @Override
    public void close() {
        lease.close();
    }
}
