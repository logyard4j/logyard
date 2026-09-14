package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class InstallationShutdownHook implements RuntimeShutdownHookRegistrar {
    private final AtomicReference<HookPhase> phase = new AtomicReference<>(HookPhase.AVAILABLE);
    private final Consumer<Thread> register;

    InstallationShutdownHook() {
        this(Runtime.getRuntime()::addShutdownHook);
    }

    InstallationShutdownHook(Consumer<Thread> register) {
        this.register = Objects.requireNonNull(register, "register");
    }

    @Override
    public boolean install(Runnable shutdown) {
        if (!phase.compareAndSet(HookPhase.AVAILABLE, HookPhase.INSTALLING)) {
            return true;
        }
        try {
            Thread hook = new Thread(null, shutdown, "logyard-runtime-shutdown", 0L, false);
            hook.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            register.accept(hook);
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
