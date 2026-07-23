package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.concurrent.atomic.AtomicBoolean;

final class InstallationShutdownHook implements RuntimeShutdownHookRegistrar {
    private final AtomicBoolean installed = new AtomicBoolean();

    @Override
    public void install(Runnable shutdown) {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "logyard-runtime-shutdown"));
        } catch (IllegalStateException | SecurityException failure) {
            installed.set(false);
            AdapterDiagnostics.shutdownHookFailure(failure);
        }
    }
}
