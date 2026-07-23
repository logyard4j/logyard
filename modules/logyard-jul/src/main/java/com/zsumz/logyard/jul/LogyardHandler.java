package com.zsumz.logyard.jul;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.jul.internal.event.JulEventMapper;
import com.zsumz.logyard.runtime.adapter.AdapterReentryGuard;
import com.zsumz.logyard.runtime.adapter.AdapterRuntimeAccess;
import com.zsumz.logyard.runtime.adapter.BorrowedAdapterRuntime;
import com.zsumz.logyard.runtime.adapter.LazyAdapterRuntime;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Bounded JUL handler that maps records directly into Logyard. */
public final class LogyardHandler extends Handler {
    private final AdapterRuntimeAccess runtime;
    private final JulEventMapper mapper;
    private final AdapterReentryGuard reentry = new AdapterReentryGuard();
    private final AtomicBoolean closed = new AtomicBoolean();

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
        if (record == null || closed.get() || !isLoggable(record) || !reentry.enter()) {
            return;
        }
        try {
            mapper.publish(runtime.runtime(), record);
        } catch (Throwable failure) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            report("Logyard JUL event capture failed", failure, ErrorManager.WRITE_FAILURE);
        } finally {
            reentry.exit();
        }
    }

    @Override
    public void flush() {
        if (closed.get() || !runtime.initialized()) {
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
        if (!closed.compareAndSet(false, true)) {
            return;
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
