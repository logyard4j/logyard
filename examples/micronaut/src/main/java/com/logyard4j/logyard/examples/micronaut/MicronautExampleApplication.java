package com.logyard4j.logyard.examples.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public final class MicronautExampleApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicronautExampleApplication.class);

    private MicronautExampleApplication() {
    }

    public static void main(String[] arguments) {
        requireSingleProvider();
        ApplicationContext application = Micronaut.run(MicronautExampleApplication.class, arguments);
        EmbeddedServer server = application.getBean(EmbeddedServer.class);
        LOGGER.atInfo().addKeyValue("phase", "startup").log("Micronaut application started");
        VerificationPort.publish(server.getPort());
    }

    private static void requireSingleProvider() {
        long providers = ServiceLoader.load(SLF4JServiceProvider.class).stream().count();
        if (providers != 1) {
            throw new IllegalStateException("expected exactly one SLF4J provider, found " + providers);
        }
    }
}
