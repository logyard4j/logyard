package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.slf4j.internal.diagnostics.ProviderDiagnostics;

import java.util.function.Supplier;

final class Slf4jCaptureFailures {
    private int count;
    private Throwable firstFailure;

    <T> T read(Supplier<T> source, T fallback) {
        try {
            return source.get();
        } catch (Throwable failure) {
            record(failure);
            return fallback;
        }
    }

    void record(Throwable failure) {
        ProviderDiagnostics.rethrowIfFatal(failure);
        count++;
        if (firstFailure == null) {
            firstFailure = failure;
        }
    }

    void merge(int additionalFailures, Throwable failure) {
        count += additionalFailures;
        if (firstFailure == null) {
            firstFailure = failure;
        }
    }

    void annotate(AttributeSet.Builder attributes) {
        if (count > 0) {
            attributes.put("logyard.slf4j.capture_failures", count);
        }
    }

    void report(String loggerName) {
        if (firstFailure != null) {
            ProviderDiagnostics.captureFailure(loggerName, firstFailure);
        }
    }
}
