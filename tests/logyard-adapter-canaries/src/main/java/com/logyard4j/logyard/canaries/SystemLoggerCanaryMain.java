package com.logyard4j.logyard.canaries;

import com.logyard4j.logyard.api.Logyard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fresh-process ServiceLoader check for Logyard's System.Logger provider. */
public final class SystemLoggerCanaryMain {
    private SystemLoggerCanaryMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected configuration path and output path");
        }
        Path config = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        System.setProperty("logyard.config", config.toString());
        System.Logger logger = System.getLogger("tests.system.service-loader");
        logger.log(System.Logger.Level.INFO, "System.Logger ServiceLoader {0}", "works");
        if (!Logyard.isInitialized()) {
            throw new AssertionError("Logyard System.LoggerFinder was not selected");
        }
        Logyard.runtime().flush();
        Logyard.shutdown();
        String content = Files.readString(output, StandardCharsets.UTF_8);
        if (!content.contains("System.Logger ServiceLoader works")) {
            throw new AssertionError("System.Logger event was not delivered");
        }
        System.out.println("System.Logger ServiceLoader canary passed");
    }
}
