package com.zsumz.logyard.examples.quarkus;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
final class QuarkusExampleLifecycle {
    private static final Logger LOGGER = Logger.getLogger(QuarkusExampleLifecycle.class);

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    void started(@Observes StartupEvent event) {
        LOGGER.info("Quarkus application started");
        publishPort(port);
    }

    private static void publishPort(int port) {
        String destination = System.getenv("LOGYARD_EXAMPLE_PORT_FILE");
        if (destination == null || destination.isBlank()) {
            System.out.println("Quarkus example listening on " + port);
            return;
        }
        try {
            Files.writeString(Path.of(destination), Integer.toString(port));
        } catch (IOException failure) {
            throw new IllegalStateException("could not publish the Quarkus verification port", failure);
        }
    }
}
