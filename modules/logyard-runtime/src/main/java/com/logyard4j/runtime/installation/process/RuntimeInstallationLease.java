package com.logyard4j.runtime.installation.process;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.lifecycle.CloseLifecycle;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.installation.GlobalRuntimeAccess;
import com.logyard4j.runtime.installation.RuntimeInstallation;

import java.util.Objects;

/** Internal ownership lease returned to bootstrap and adapter façades. */
public final class RuntimeInstallationLease implements AutoCloseable {
    private final RuntimeInstallationManager manager;
    private final GlobalRuntimeAccess globalRuntime;
    private final RuntimeOwner owner;
    private final RuntimeInstallation installation;
    private final LogyardRuntime runtime;
    private final CloseLifecycle lifecycle = new CloseLifecycle();

    static RuntimeInstallationLease managed(
            RuntimeInstallationManager manager,
            GlobalRuntimeAccess globalRuntime,
            RuntimeOwner owner,
            RuntimeInstallation installation) {
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
            RuntimeInstallation installation,
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
        if (lifecycle.closed()) {
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
        if (!lifecycle.beginClose() || installation == null) {
            return;
        }
        manager.release(owner, installation);
    }
}
