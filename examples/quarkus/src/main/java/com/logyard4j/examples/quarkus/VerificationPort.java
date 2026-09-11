package com.logyard4j.examples.quarkus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Publishes the ephemeral server port when automated verification requests it. */
final class VerificationPort {
    private VerificationPort() {
    }

    static void publish(int port) {
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
