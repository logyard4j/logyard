package com.logyard4j.examples.micronaut;

import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
final class DeferredShutdown {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredShutdown.class);
    private static final long RESPONSE_GRACE_PERIOD_NANOS = 100_000_000L;

    private final ApplicationContext application;
    private final AtomicBoolean stopping = new AtomicBoolean();

    DeferredShutdown(ApplicationContext application) {
        this.application = application;
    }

    void afterResponse() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform()
                .name("logyard-micronaut-example-shutdown")
                .start(this::closeAfterResponse);
    }

    private void closeAfterResponse() {
        LockSupport.parkNanos(RESPONSE_GRACE_PERIOD_NANOS);
        application.close();
        LOGGER.atInfo().addKeyValue("phase", "shutdown").log("Micronaut shutdown flush");
    }
}
