package com.logyard4j.examples.slf4j;

import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public final class Slf4jExampleApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(Slf4jExampleApplication.class);

    private Slf4jExampleApplication() {
    }

    public static void main(String[] arguments) {
        requireSingleProvider();
        LOGGER.atInfo().addKeyValue("phase", "startup").log("plain SLF4J startup");
        LOGGER.atInfo().addKeyValue("request.id", "plain-request-1").addKeyValue("operation", "example").log("plain SLF4J application event");
        LOGGER.atError().addKeyValue("request.id", "plain-request-1").setCause(new IllegalStateException("expected plain example failure")).log("plain SLF4J failure");
        LOGGER.atInfo().addKeyValue("phase", "shutdown").log("plain SLF4J shutdown flush");
    }

    private static void requireSingleProvider() {
        long providers = ServiceLoader.load(SLF4JServiceProvider.class).stream().count();
        if (providers != 1) {
            throw new IllegalStateException("expected exactly one SLF4J provider, found " + providers);
        }
    }
}
