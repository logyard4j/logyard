package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Process-wide coordinator for runtime identity, configuration handoff, and ownership. */
public final class RuntimeInstallationManager {
    private static final RuntimeInstallationManager PROCESS = new RuntimeInstallationManager(
            new LogyardGlobalRuntimeAccess(),
            new InstallationShutdownHook(),
            System::getenv);

    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeShutdownHookRegistrar shutdownHooks;
    private final Supplier<Map<String, String>> environment;
    private final EnumMap<RuntimeOwner, Integer> leaseCounts = new EnumMap<>(RuntimeOwner.class);

    private ManagedRuntimeInstallation installation;
    private RuntimeOwner configurationAuthority;

    RuntimeInstallationManager(
            GlobalRuntimeAccess globalRuntime,
            RuntimeShutdownHookRegistrar shutdownHooks,
            Supplier<Map<String, String>> environment) {
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        this.shutdownHooks = Objects.requireNonNull(shutdownHooks, "shutdownHooks");
        this.environment = Objects.requireNonNull(environment, "environment");
        for (RuntimeOwner owner : RuntimeOwner.values()) {
            leaseCounts.put(owner, 0);
        }
    }

    public static RuntimeInstallationManager process() {
        return PROCESS;
    }

    public RuntimeInstallationLease acquireApplication(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.APPLICATION, request);
    }

    public RuntimeInstallationLease acquireFramework(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.FRAMEWORK, request);
    }

    public RuntimeInstallationLease acquireAdapter(ConfigurationInstallationRequest request) {
        return acquire(RuntimeOwner.ADAPTER, request);
    }

    synchronized boolean isActive(ManagedRuntimeInstallation candidate) {
        return installation == candidate && globalRuntime.current() == candidate.runtime();
    }

    synchronized int leaseCount(RuntimeOwner owner) {
        return leaseCounts.get(owner);
    }

    void release(RuntimeOwner owner, ManagedRuntimeInstallation candidate) {
        synchronized (this) {
            if (installation != candidate) {
                return;
            }
            int count = leaseCounts.get(owner);
            if (count <= 0) {
                throw new IllegalStateException("Logyard runtime lease accounting underflow for " + owner);
            }
            leaseCounts.put(owner, count - 1);
            if (totalLeases() > 0) {
                return;
            }
            installation = null;
            configurationAuthority = null;
            candidate.close(globalRuntime);
        }
    }

    private synchronized RuntimeInstallationLease acquire(
            RuntimeOwner owner,
            ConfigurationInstallationRequest request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        discardStaleInstallation();
        if (installation != null) {
            if (owner.canReplace(configurationAuthority)) {
                installation.reconfigure(request);
                configurationAuthority = owner;
            }
            increment(owner);
            return RuntimeInstallationLease.managed(this, globalRuntime, owner, installation);
        }

        LogyardRuntime existing = globalRuntime.current();
        if (existing != null) {
            if (owner == RuntimeOwner.ADAPTER) {
                return RuntimeInstallationLease.borrowed(globalRuntime, existing);
            }
            throw new IllegalStateException("Logyard is already initialized outside the runtime installation manager");
        }

        ManagedRuntimeInstallation candidate = ManagedRuntimeInstallation.open(request, environment.get());
        try {
            globalRuntime.install(candidate.runtime());
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(candidate, failure);
            throw failure;
        }
        installation = candidate;
        configurationAuthority = owner;
        increment(owner);
        try {
            shutdownHooks.install(this::shutdownAtExit);
        } catch (RuntimeException | Error failure) {
            installation = null;
            configurationAuthority = null;
            clearLeaseCounts();
            closeAfterFailure(candidate, failure);
            throw failure;
        }
        return RuntimeInstallationLease.managed(this, globalRuntime, owner, candidate);
    }

    private void discardStaleInstallation() {
        if (installation == null || globalRuntime.current() == installation.runtime()) {
            return;
        }
        ManagedRuntimeInstallation stale = installation;
        installation = null;
        configurationAuthority = null;
        clearLeaseCounts();
        stale.close(globalRuntime);
    }

    private synchronized void shutdownAtExit() {
        if (installation == null) {
            return;
        }
        ManagedRuntimeInstallation closing = installation;
        installation = null;
        configurationAuthority = null;
        clearLeaseCounts();
        try {
            closing.close(globalRuntime);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("runtime", "shutdown", failure);
        }
    }

    private void increment(RuntimeOwner owner) {
        leaseCounts.put(owner, Math.addExact(leaseCounts.get(owner), 1));
    }

    private int totalLeases() {
        int total = 0;
        for (int count : leaseCounts.values()) {
            total = Math.addExact(total, count);
        }
        return total;
    }

    private void clearLeaseCounts() {
        leaseCounts.replaceAll((owner, ignored) -> 0);
    }

    private void closeAfterFailure(ManagedRuntimeInstallation candidate, Throwable primaryFailure) {
        try {
            candidate.close(globalRuntime);
        } catch (Throwable cleanupFailure) {
            AdapterDiagnostics.rethrowIfFatal(cleanupFailure);
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }
}
