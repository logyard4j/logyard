package com.logyard4j.logyard.canaries;

import com.logyard4j.logyard.runtime.bootstrap.ConfigurationDiscovery;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;

import java.nio.file.Path;

/** Verifies oversized public source identifiers fail normally under a constrained heap. */
public final class ConfigurationSourceBoundednessMain {
    private ConfigurationSourceBoundednessMain() {
    }

    public static void main(String[] args) {
        rejectOversizedDescription();
        rejectOversizedResource();
        rejectOversizedDiscoveryLocation();
        System.out.println("Configuration source boundedness verification passed under constrained heap");
    }

    private static void rejectOversizedDescription() {
        String description = " x".repeat(17_500_000);
        expectRejection(
                () -> LogyardConfigurationSource.text(description, "schema = 1", Path.of(".")),
                "configuration source description exceeds");
    }

    private static void rejectOversizedResource() {
        String resource = "/x".repeat(17_500_000);
        expectRejection(
                () -> LogyardConfigurationSource.classpath(
                        ConfigurationSourceBoundednessMain.class.getClassLoader(), resource, Path.of(".")),
                "classpath resource exceeds");
    }

    private static void rejectOversizedDiscoveryLocation() {
        String location = " x".repeat(17_500_000);
        System.setProperty(ConfigurationDiscovery.SYSTEM_PROPERTY, location);
        try {
            expectRejection(ConfigurationDiscovery::resolve, "configuration file location exceeds");
            expectRejection(ConfigurationDiscovery::find, "configuration file location exceeds");
        } finally {
            System.clearProperty(ConfigurationDiscovery.SYSTEM_PROPERTY);
        }
    }

    private static void expectRejection(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError("oversized source identifier was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) {
                throw new AssertionError("source rejection lost its public message", expected);
            }
        }
    }
}
