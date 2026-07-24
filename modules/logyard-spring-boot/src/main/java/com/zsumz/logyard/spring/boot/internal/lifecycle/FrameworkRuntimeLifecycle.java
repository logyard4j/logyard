package com.zsumz.logyard.spring.boot.internal.lifecycle;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;
import java.util.Objects;

/** Owns one replaceable Spring lease without ever replacing the shared runtime identity. */
public final class FrameworkRuntimeLifecycle {
    private final JulCapture julCapture = new JulCapture();
    private RuntimeBundle bundle;

    public synchronized void startEarly() {
        if (bundle != null && bundle.active()) {
            return;
        }
        RuntimeBundle acquired = LogyardBootstrap.acquire(
                RuntimeOwner.FRAMEWORK,
                ConfigurationDiscovery.resolve());
        try {
            julCapture.start(acquired.runtime());
            bundle = acquired;
        } catch (Throwable failure) {
            SpringLifecycleBoundary.invoke("failed early runtime lease release", acquired::close);
            throw failure;
        }
    }

    public synchronized void configure(LogyardConfigurationSource source) {
        RuntimeBundle replacement = LogyardBootstrap.acquire(
                RuntimeOwner.FRAMEWORK,
                Objects.requireNonNull(source, "source"));
        try {
            julCapture.start(replacement.runtime());
        } catch (Throwable failure) {
            SpringLifecycleBoundary.invoke("failed configured runtime lease release", replacement::close);
            throw failure;
        }
        RuntimeBundle previous = bundle;
        bundle = replacement;
        if (previous != null) {
            SpringLifecycleBoundary.invoke("early runtime lease release", previous::close);
        }
    }

    public synchronized LogyardRuntime runtime() {
        if (bundle == null || !bundle.active()) {
            startEarly();
        }
        return bundle.runtime();
    }

    public synchronized void close() {
        RuntimeBundle closing = bundle;
        bundle = null;
        if (closing == null) {
            return;
        }
        SpringLifecycleBoundary.invoke("Spring shutdown flush", () -> closing.runtime().flush());
        SpringLifecycleBoundary.invoke("Spring JUL capture release", julCapture::close);
        SpringLifecycleBoundary.invoke("Spring runtime lease release", closing::close);
    }
}
