package com.zsumz.logyard.slf4j;

import com.zsumz.logyard.runtime.adapter.LazyAdapterRuntime;
import com.zsumz.logyard.slf4j.internal.context.ContextSnapshotPolicy;
import com.zsumz.logyard.slf4j.internal.context.LogyardMdcAdapter;
import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;
import com.zsumz.logyard.slf4j.internal.event.Slf4jEventMapper;
import com.zsumz.logyard.slf4j.internal.factory.SwitchableLoggerFactory;
import com.zsumz.logyard.slf4j.internal.factory.LogyardLoggerFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/** ServiceLoader entry point for SLF4J 2.x. */
public final class LogyardServiceProvider implements SLF4JServiceProvider, AutoCloseable {
    /** Non-final by SLF4J convention so the API can inspect the compatibility line. */
    public static String REQUESTED_API_VERSION = "2.0.99";

    private final SwitchableLoggerFactory loggerFactory = new SwitchableLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final LogyardMdcAdapter mdcAdapter = new LogyardMdcAdapter();
    private State state = State.NEW;
    private Throwable initializationFailure;
    private LazyAdapterRuntime runtime;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }

    @Override
    public synchronized void initialize() {
        if (state == State.READY) {
            return;
        }
        if (state == State.INITIALIZING) {
            throw new IllegalStateException("recursive Logyard SLF4J provider initialization");
        }
        if (state == State.FAILED) {
            throw new IllegalStateException(
                    "Logyard SLF4J provider initialization already failed",
                    initializationFailure);
        }
        state = State.INITIALIZING;
        LazyAdapterRuntime resolved = null;
        try {
            resolved = new LazyAdapterRuntime("slf4j2");
            ContextSnapshotPolicy contextPolicy = new ContextSnapshotPolicy(resolved.contextPolicySource());
            Slf4jEventMapper mapper = new Slf4jEventMapper(mdcAdapter, contextPolicy);
            loggerFactory.install(new LogyardLoggerFactory(resolved, mapper));
            runtime = resolved;
            state = State.READY;
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            closeAfterInitializationFailure(resolved, failure);
            initializationFailure = failure;
            loggerFactory.fail(failure);
            state = State.FAILED;
            ProviderDiagnostics.initializationFailure(failure);
            throw new IllegalStateException("failed to initialize the Logyard SLF4J provider", failure);
        }
    }

    private static void closeAfterInitializationFailure(
            LazyAdapterRuntime resolved,
            Throwable initializationFailure) {
        if (resolved == null) {
            return;
        }
        try {
            resolved.close();
        } catch (Throwable cleanupFailure) {
            ProviderDiagnostics.rethrowIfFatal(cleanupFailure);
            initializationFailure.addSuppressed(cleanupFailure);
        }
    }

    /** Exposed for diagnostics and provider tests; SLF4J itself has no shutdown callback. */
    public synchronized boolean ownsRuntime() {
        return runtime != null && runtime.ownsRuntime();
    }

    /**
     * Releases the provider's adapter lease for embedded containers and lifecycle benchmarks.
     *
     * <p>SLF4J does not define a shutdown callback, so ordinary applications rely on Logyard's
     * process lifecycle instead.</p>
     */
    @Override
    public synchronized void close() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }

    private enum State {
        NEW,
        INITIALIZING,
        READY,
        FAILED
    }
}
