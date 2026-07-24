package com.zsumz.logyard.quarkus.runtime.lifecycle;

import com.zsumz.logyard.quarkus.runtime.logging.QuarkusLogHandler;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;

/** Owns the Quarkus framework lease and its non-owning logging handler as one lifecycle. */
public final class QuarkusRuntimeLifecycle implements AutoCloseable {
    private final RuntimeBundle bundle;
    private final QuarkusLogHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean();

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

    /** Flushes capture, retires the handler, and releases the framework lease exactly once. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeBoundary("shutdown flush", () -> bundle.runtime().flush());
        closeBoundary("handler retirement", handler::close);
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
