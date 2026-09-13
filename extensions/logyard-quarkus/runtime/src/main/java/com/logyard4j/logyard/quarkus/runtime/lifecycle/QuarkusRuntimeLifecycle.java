package com.logyard4j.logyard.quarkus.runtime.lifecycle;

import com.logyard4j.logyard.api.lifecycle.CloseLifecycle;
import com.logyard4j.logyard.quarkus.runtime.logging.QuarkusLogHandler;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeOwner;
import com.logyard4j.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.util.logging.Handler;

/** Owns the Quarkus framework lease and its non-owning logging handler as one lifecycle. */
public final class QuarkusRuntimeLifecycle implements AutoCloseable {
    private final RuntimeBundle bundle;
    private final QuarkusLogHandler handler;
    private final CloseLifecycle lifecycle = new CloseLifecycle();

    private QuarkusRuntimeLifecycle(RuntimeBundle bundle) {
        this.bundle = Objects.requireNonNull(bundle, "bundle");
        handler = new QuarkusLogHandler(bundle.runtime());
    }

    /**
     * Acquires one framework lease and creates its handler.
     *
     * @param source resolved Logyard configuration
     * @return active Quarkus lifecycle
     */
    public static QuarkusRuntimeLifecycle start(LogyardConfigurationSource source) {
        RuntimeBundle acquired = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, Objects.requireNonNull(source, "source"));
        try {
            return new QuarkusRuntimeLifecycle(acquired);
        } catch (Throwable failure) {
            closeBoundary("failed startup lease release", acquired::close);
            throw failure;
        }
    }

    /**
     * Returns the handler registered with Quarkus.
     *
     * @return non-owning handler
     */
    public Handler handler() {
        return handler;
    }

    /** Retires the handler, flushes captured events, and releases the framework lease exactly once. */
    @Override
    public void close() {
        if (!lifecycle.beginClose()) {
            return;
        }
        closeBoundary("handler retirement", handler::close);
        closeBoundary("shutdown flush", () -> bundle.runtime().flush());
        closeBoundary("runtime lease release", bundle::close);
    }

    private static void closeBoundary(String operation, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("quarkus", operation, failure);
        }
    }
}
