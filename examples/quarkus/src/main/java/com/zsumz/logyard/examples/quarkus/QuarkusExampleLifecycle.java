package com.zsumz.logyard.examples.quarkus;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
final class QuarkusExampleLifecycle {
    private static final Logger LOGGER = Logger.getLogger(QuarkusExampleLifecycle.class);

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    void started(@Observes StartupEvent event) {
        LOGGER.info("Quarkus application started");
        VerificationPort.publish(port);
    }
}
