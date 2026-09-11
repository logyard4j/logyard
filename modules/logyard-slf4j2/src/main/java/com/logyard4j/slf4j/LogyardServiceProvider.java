package com.logyard4j.slf4j;

import com.logyard4j.runtime.adapter.LazyAdapterRuntime;
import com.logyard4j.slf4j.internal.context.ContextSnapshotPolicy;
import com.logyard4j.slf4j.internal.context.LogyardMdcAdapter;
import com.logyard4j.slf4j.internal.diagnostics.ProviderDiagnostics;
import com.logyard4j.slf4j.internal.event.Slf4jEventMapper;
import com.logyard4j.slf4j.internal.factory.SwitchableLoggerFactory;
import com.logyard4j.slf4j.internal.factory.LogyardLoggerFactory;
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
    private ProviderState state = AwaitingInitialization.INSTANCE;

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
        if (state instanceof Ready || state == Closed.INSTANCE) {
            return;
        }
        if (state == Initializing.INSTANCE) {
            throw new IllegalStateException("recursive Logyard SLF4J provider initialization");
        }
        if (state instanceof Failed failed) {
            throw new IllegalStateException("Logyard SLF4J provider initialization already failed", failed.failure());
        }
        state = Initializing.INSTANCE;
        LazyAdapterRuntime resolved = null;
        try {
            resolved = new LazyAdapterRuntime("slf4j2");
            ContextSnapshotPolicy contextPolicy = new ContextSnapshotPolicy(resolved.contextPolicySource());
            Slf4jEventMapper mapper = new Slf4jEventMapper(mdcAdapter, contextPolicy);
            loggerFactory.install(new LogyardLoggerFactory(resolved, mapper));
            state = new Ready(resolved);
        } catch (Throwable failure) {
            ProviderDiagnostics.rethrowIfFatal(failure);
            closeAfterInitializationFailure(resolved, failure);
            loggerFactory.fail(failure);
            state = new Failed(failure);
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
        return state instanceof Ready ready && ready.runtime().ownsRuntime();
    }

    /**
     * Releases the provider's adapter lease for embedded containers and lifecycle benchmarks.
     *
     * <p>SLF4J does not define a shutdown callback, so ordinary applications rely on Logyard's
     * process lifecycle instead.</p>
     */
    @Override
    public synchronized void close() {
        if (state instanceof Ready ready) {
            ready.runtime().close();
            state = Closed.INSTANCE;
        }
    }

    private sealed interface ProviderState permits AwaitingInitialization, Closed, Failed, Initializing, Ready {
    }

    private enum AwaitingInitialization implements ProviderState {
        INSTANCE
    }

    private enum Initializing implements ProviderState {
        INSTANCE
    }

    private enum Closed implements ProviderState {
        INSTANCE
    }

    private record Ready(LazyAdapterRuntime runtime) implements ProviderState {
    }

    private record Failed(Throwable failure) implements ProviderState {
    }
}
