package com.zsumz.logyard.runtime.bootstrap;

import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;
import com.zsumz.logyard.runtime.installation.RuntimeInstallationLease;
import com.zsumz.logyard.runtime.installation.RuntimeInstallationManager;

import java.nio.file.Path;
import java.util.Objects;

/** Entry point for acquiring ownership leases on the process-wide Logyard runtime. */
public final class LogyardBootstrap {
    private LogyardBootstrap() {
    }

    /**
     * Acquires an application lease using deterministic discovery and safe defaults.
     *
     * @return application-owned runtime bundle
     */
    public static RuntimeBundle start() {
        return acquire(RuntimeOwner.APPLICATION, ConfigurationDiscovery.resolve());
    }

    /**
     * Acquires an application lease using a filesystem configuration.
     *
     * @param source configuration path
     * @return application-owned runtime bundle
     */
    public static RuntimeBundle start(Path source) {
        return start(LogyardConfigurationSource.file(Objects.requireNonNull(source, "source")));
    }

    /**
     * Acquires an application lease using a file, classpath, text, framework, or default source.
     *
     * @param source configuration source
     * @return application-owned runtime bundle
     */
    public static RuntimeBundle start(LogyardConfigurationSource source) {
        return acquire(RuntimeOwner.APPLICATION, source);
    }

    /**
     * Acquires a typed ownership lease, installing or reconfiguring the same process-wide runtime.
     *
     * <p>Framework acquisition applies the supplied source in place. Adapter acquisition borrows
     * an externally installed application runtime when one predates the installation manager.</p>
     *
     * @param owner lifecycle participant
     * @param source configuration source
     * @return ownership-aware runtime bundle
     */
    public static RuntimeBundle acquire(RuntimeOwner owner, LogyardConfigurationSource source) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        ConfigurationInstallationRequest request = new ConfigurationInstallationRequest(
                source.description(),
                source.watchPath(),
                source.identity(),
                source::snapshot);
        RuntimeInstallationManager manager = RuntimeInstallationManager.process();
        RuntimeInstallationLease lease = switch (owner) {
            case APPLICATION -> manager.acquireApplication(request);
            case FRAMEWORK -> manager.acquireFramework(request);
            case ADAPTER -> manager.acquireAdapter(request);
        };
        return new RuntimeBundle(source, lease);
    }
}
