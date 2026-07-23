package com.zsumz.logyard.runtime.adapter;

import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.diagnostics.AdapterDiagnostics;

final class AdapterRuntimeCloser {
    void closeReleased(RuntimeBundle bundle) {
        Throwable failure = close(bundle);
        if (failure != null) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            throw new IllegalStateException("failed to close shared Logyard adapter runtime", failure);
        }
    }

    void closeStale(RuntimeBundle bundle) {
        report(bundle, "stale runtime cleanup");
    }

    void closeAtShutdown(RuntimeBundle bundle) {
        report(bundle, "shutdown");
    }

    private static void report(RuntimeBundle bundle, String operation) {
        Throwable failure = close(bundle);
        if (failure != null) {
            AdapterDiagnostics.rethrowIfFatal(failure);
            AdapterDiagnostics.adapterFailure("shared", operation, failure);
        }
    }

    private static Throwable close(RuntimeBundle bundle) {
        try {
            bundle.close();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
