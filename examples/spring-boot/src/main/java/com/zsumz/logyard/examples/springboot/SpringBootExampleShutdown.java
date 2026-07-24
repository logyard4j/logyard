package com.zsumz.logyard.examples.springboot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
final class SpringBootExampleShutdown {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringBootExampleShutdown.class);
    private static final long RESPONSE_GRACE_PERIOD_NANOS = 100_000_000L;

    private final ConfigurableApplicationContext application;
    private final AtomicBoolean stopping = new AtomicBoolean();

    SpringBootExampleShutdown(ConfigurableApplicationContext application) {
        this.application = application;
    }

    void afterResponse() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform()
                .name("logyard-spring-boot-example-shutdown")
                .start(this::closeAfterResponse);
    }

    private void closeAfterResponse() {
        LockSupport.parkNanos(RESPONSE_GRACE_PERIOD_NANOS);
        LOGGER.atInfo().addKeyValue("phase", "shutdown").log("Spring Boot shutdown flush");
        application.close();
    }
}
