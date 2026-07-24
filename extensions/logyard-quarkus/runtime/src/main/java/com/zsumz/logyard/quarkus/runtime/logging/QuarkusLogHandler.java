package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.adapter.AdapterReentryGuard;
import com.zsumz.logyard.runtime.context.ContextPolicyRegistry;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;
import com.zsumz.logyard.runtime.adapter.PublicationGate;

import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Non-owning JBoss Log Manager handler installed by the Quarkus recorder. */
public final class QuarkusLogHandler extends Handler {
    private static final Duration PUBLICATION_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final LogyardRuntime runtime;
    private final QuarkusEventMapper mapper;
    private final AdapterReentryGuard reentry = new AdapterReentryGuard();
    private final PublicationGate publications = new PublicationGate();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a handler that borrows a framework-owned runtime.
     *
     * @param runtime shared Logyard runtime
     */
    public QuarkusLogHandler(LogyardRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        mapper = new QuarkusEventMapper(ContextPolicyRegistry.sourceFor(runtime));
        setLevel(java.util.logging.Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || closed.get() || !isLoggable(record) || !reentry.enter()) {
            return;
        }
        if (!publications.tryEnter()) {
            reentry.exit();
            return;
        }
        try {
            mapper.publish(runtime, record);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("quarkus", "event capture", failure);
        } finally {
            publications.exit();
            reentry.exit();
        }
    }

    @Override
    public void flush() {
        if (closed.get()) {
            return;
        }
        try {
            runtime.flush();
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("quarkus", "flush", failure);
        }
    }

    /**
     * Retires this handler without closing the shared runtime; the recorder's lifecycle owns the
     * corresponding framework lease.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (!publications.retireAndAwaitDrain(PUBLICATION_DRAIN_TIMEOUT)) {
                AdapterDiagnostics.adapterFailure(
                        "quarkus",
                        "handler retirement",
                        new IllegalStateException("Quarkus publication drain timed out"));
            }
        }
    }
}
