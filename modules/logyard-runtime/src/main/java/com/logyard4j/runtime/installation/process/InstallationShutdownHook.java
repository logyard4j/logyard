package com.logyard4j.runtime.installation.process;

import com.logyard4j.runtime.diagnostics.AdapterDiagnostics;

import java.util.concurrent.atomic.AtomicReference;

final class InstallationShutdownHook implements RuntimeShutdownHookRegistrar {
    private final AtomicReference<HookPhase> phase = new AtomicReference<>(HookPhase.AVAILABLE);

    @Override
    public boolean install(Runnable shutdown) {
        if (!phase.compareAndSet(HookPhase.AVAILABLE, HookPhase.INSTALLING)) {
            return true;
        }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "logyard-runtime-shutdown"));
            phase.set(HookPhase.INSTALLED);
            return true;
        } catch (IllegalStateException | SecurityException failure) {
            phase.set(HookPhase.AVAILABLE);
            AdapterDiagnostics.shutdownHookFailure(failure);
            return false;
        }
    }

    private enum HookPhase {
        AVAILABLE,
        INSTALLING,
        INSTALLED
    }
}
