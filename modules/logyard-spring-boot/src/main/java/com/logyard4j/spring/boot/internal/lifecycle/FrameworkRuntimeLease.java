package com.logyard4j.spring.boot.internal.lifecycle;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.bootstrap.RuntimeOwner;

/** Couples one framework runtime bundle to the JUL capture lease that must follow its lifecycle. */
final class FrameworkRuntimeLease {
    private final RuntimeBundle bundle;
    private final JulCapture.Lease julCapture;

    private FrameworkRuntimeLease(RuntimeBundle bundle, JulCapture.Lease julCapture) {
        this.bundle = bundle;
        this.julCapture = julCapture;
    }

    static FrameworkRuntimeLease acquire(
            FrameworkRuntimeAcquirer runtimeAcquirer,
            JulCapture capture,
            LogyardConfigurationSource source,
            String rejectedCaptureComponent,
            String rejectedRuntimeComponent) {
        RuntimeBundle acquiredBundle = null;
        JulCapture.Lease acquiredCapture = null;
        try {
            acquiredBundle = runtimeAcquirer.acquire(RuntimeOwner.FRAMEWORK, source);
            acquiredCapture = capture.acquire(acquiredBundle.runtime());
            return new FrameworkRuntimeLease(acquiredBundle, acquiredCapture);
        } catch (Throwable failure) {
            if (acquiredCapture != null) {
                JulCapture.Lease rejectedCapture = acquiredCapture;
                SpringLifecycleBoundary.invoke(rejectedCaptureComponent, rejectedCapture::close);
            }
            if (acquiredBundle != null) {
                RuntimeBundle rejectedBundle = acquiredBundle;
                SpringLifecycleBoundary.invoke(rejectedRuntimeComponent, rejectedBundle::close);
            }
            throw failure;
        }
    }

    LogyardRuntime runtime() {
        return bundle.runtime();
    }

    void release(String captureComponent, String runtimeComponent) {
        SpringLifecycleBoundary.invoke(captureComponent, julCapture::close);
        SpringLifecycleBoundary.invoke(runtimeComponent, bundle::close);
    }

    void close(String captureComponent, String flushComponent, String runtimeComponent) {
        SpringLifecycleBoundary.invoke(captureComponent, julCapture::close);
        SpringLifecycleBoundary.invoke(flushComponent, () -> runtime().flush());
        SpringLifecycleBoundary.invoke(runtimeComponent, bundle::close);
    }
}
