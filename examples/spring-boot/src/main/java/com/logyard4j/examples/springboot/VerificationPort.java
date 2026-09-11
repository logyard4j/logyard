package com.logyard4j.examples.springboot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Publishes the ephemeral server port when automated verification requests it. */
final class VerificationPort {
    private VerificationPort() {
    }

    static void publish(String port) {
        String destination = System.getenv("LOGYARD_EXAMPLE_PORT_FILE");
        if (destination == null || destination.isBlank()) {
            System.out.println("Spring Boot example listening on " + port);
            return;
        }
        try {
            Files.writeString(Path.of(destination), port);
        } catch (IOException failure) {
            throw new IllegalStateException("could not publish the Spring Boot verification port", failure);
        }
    }
}
