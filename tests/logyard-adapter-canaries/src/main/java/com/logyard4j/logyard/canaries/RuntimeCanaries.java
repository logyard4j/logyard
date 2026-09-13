package com.logyard4j.logyard.canaries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs constrained-heap and discovery probes on the classpath built and resolved by Zolt. */
public final class RuntimeCanaries {
    private RuntimeCanaries() {
    }

    public static void main(String[] arguments) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "runtime-canaries")).toRealPath();
        Path parentConfig = directory.resolve("launcher.toml");
        Files.writeString(parentConfig, """
                schema = 1
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "error", outputs = ["console"] }
                [outputs.console]
                type = "console"
                """);
        System.setProperty("logyard.config", parentConfig.toString());
        verifySlf4jShape();
        Path output = directory.resolve("events.jsonl");
        Path config = directory.resolve("logyard.toml");
        Files.deleteIfExists(output);
        Files.writeString(config, """
                schema = 1
                [service]
                name = "system-logger-canary"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = false
                flush = "0s"
                """.formatted(output.toString().replace("\\", "\\\\").replace("\"", "\\\"")));
        String source = "-Dlogyard.config=" + config;
        run(AdversarialBoundednessMain.class, List.of(source, "-Xmx64m"));
        run(AdapterBoundednessMain.class, List.of(source, "-Xmx64m", "-Xss256k"));
        run(SystemLoggerCanaryMain.class, List.of(), config.toString(), output.toString());
    }

    private static void verifySlf4jShape() throws ClassNotFoundException {
        ClassLoader loader = RuntimeCanaries.class.getClassLoader();
        Class<?> logger = Class.forName("com.logyard4j.logyard.slf4j.internal.logger.LogyardSlf4jLogger", false, loader);
        Class<?> eventAware = Class.forName("org.slf4j.spi.LoggingEventAware", false, loader);
        Class<?> locationAware = Class.forName("org.slf4j.spi.LocationAwareLogger", false, loader);
        if (!eventAware.isAssignableFrom(logger) || locationAware.isAssignableFrom(logger)) {
            throw new AssertionError("SLF4J provider must support LoggingEventAware without LocationAwareLogger");
        }
        System.out.println("SLF4J provider shape canary passed");
    }

    private static void run(Class<?> main, List<String> vmOptions, String... arguments)
            throws IOException, InterruptedException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.addAll(vmOptions);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).inheritIO().start();
        try {
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                throw new AssertionError(main.getSimpleName() + " exceeded its 90-second deadline");
            }
            if (child.exitValue() != 0) {
                throw new AssertionError(main.getSimpleName() + " failed with exit " + child.exitValue());
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
