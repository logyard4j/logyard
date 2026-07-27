package com.zsumz.logyard.jul;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.lifecycle.CloseLifecycle;
import com.zsumz.logyard.jul.internal.event.JulEventMapper;
import com.zsumz.logyard.runtime.adapter.AdapterReentryGuard;
import com.zsumz.logyard.runtime.adapter.AdapterRuntimeAccess;
import com.zsumz.logyard.runtime.adapter.BorrowedAdapterRuntime;
import com.zsumz.logyard.runtime.adapter.LazyAdapterRuntime;
import com.zsumz.logyard.runtime.adapter.PublicationGate;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.time.Duration;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Bounded JUL handler that maps records directly into Logyard. */
public final class LogyardHandler extends Handler {
    private static final Duration PUBLICATION_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final AdapterRuntimeAccess runtime;
    private final JulEventMapper mapper;
    private final AdapterReentryGuard reentry = new AdapterReentryGuard();
    private final PublicationGate publications = new PublicationGate();
    private final CloseLifecycle lifecycle = new CloseLifecycle();

    /** Logging-properties-compatible constructor with lazy process bootstrap. */
    public LogyardHandler() {
        this(new LazyAdapterRuntime("jul"));
    }

    /** Non-owning constructor for dependency injection and application-managed runtimes. */
    public LogyardHandler(LogyardRuntime runtime) {
        this(new BorrowedAdapterRuntime(Objects.requireNonNull(runtime, "runtime")));
    }

    LogyardHandler(AdapterRuntimeAccess runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        mapper = new JulEventMapper();
        setLevel(java.util.logging.Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || lifecycle.closed() || !isLoggable(record) || !reentry.enter()) {
            return;
        }
        if (!publications.tryEnter()) {
            reentry.exit();
            return;
        }
        try {
            mapper.publish(runtime.runtime(), record);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            report("Logyard JUL event capture failed", failure, ErrorManager.WRITE_FAILURE);
        } finally {
            publications.exit();
            reentry.exit();
        }
    }

    @Override
    public void flush() {
        if (lifecycle.closed() || !runtime.initialized()) {
            return;
        }
        try {
            runtime.runtime().flush();
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            report("Logyard JUL flush failed", failure, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public void close() {
        if (!lifecycle.beginClose()) {
            return;
        }
        if (!publications.retireAndAwaitDrain(PUBLICATION_DRAIN_TIMEOUT)) {
            report(
                    "Logyard JUL close timed out waiting for in-flight publication",
                    new IllegalStateException("JUL publication drain timed out"),
                    ErrorManager.CLOSE_FAILURE);
        }
        try {
            runtime.close();
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            report("Logyard JUL close failed", failure, ErrorManager.CLOSE_FAILURE);
        }
    }

    private void report(String message, Throwable failure, int code) {
        Exception exception = failure instanceof Exception checked
                ? checked
                : new IllegalStateException(failure);
        try {
            reportError(message, exception, code);
        } catch (Throwable diagnosticFailure) {
            AdapterDiagnostics.rethrowIfFatal(diagnosticFailure);
            AdapterDiagnostics.adapterFailure("jul", "diagnostic", diagnosticFailure);
        }
    }
}
