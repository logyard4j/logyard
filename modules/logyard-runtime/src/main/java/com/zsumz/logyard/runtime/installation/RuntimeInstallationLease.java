package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal ownership lease returned to bootstrap and adapter façades. */
public final class RuntimeInstallationLease implements AutoCloseable {
    private final RuntimeInstallationManager manager;
    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeOwner owner;
    private final ManagedRuntimeInstallation installation;
    private final LogyardRuntime runtime;
    private final AtomicBoolean closed = new AtomicBoolean();

    static RuntimeInstallationLease managed(
            RuntimeInstallationManager manager,
            GlobalRuntimeAccess globalRuntime,
            RuntimeOwner owner,
            ManagedRuntimeInstallation installation) {
        return new RuntimeInstallationLease(manager, globalRuntime, owner, installation, installation.runtime());
    }

    static RuntimeInstallationLease borrowed(
            GlobalRuntimeAccess globalRuntime,
            LogyardRuntime runtime) {
        return new RuntimeInstallationLease(null, globalRuntime, RuntimeOwner.ADAPTER, null, runtime);
    }

    private RuntimeInstallationLease(
            RuntimeInstallationManager manager,
            GlobalRuntimeAccess globalRuntime,
            RuntimeOwner owner,
            ManagedRuntimeInstallation installation,
            LogyardRuntime runtime) {
        this.manager = manager;
        this.globalRuntime = Objects.requireNonNull(globalRuntime, "globalRuntime");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.installation = installation;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public LogyardRuntime runtime() {
        return runtime;
    }

    public boolean active() {
        if (closed.get()) {
            return false;
        }
        return installation == null
                ? globalRuntime.current() == runtime
                : manager.isActive(installation);
    }

    public boolean ownsRuntime() {
        return installation != null;
    }

    public boolean watchesConfiguration() {
        return active() && installation != null && installation.watchesConfiguration();
    }

    public ReloadResult reloadNow() {
        if (installation == null) {
            throw new IllegalStateException("this runtime lease has no active managed configuration");
        }
        return active() ? installation.reloadNow() : ReloadResult.REJECTED;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || installation == null) {
            return;
        }
        manager.release(owner, installation);
    }
}
