package com.zsumz.logyard.spring.boot.nativeimage;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/** Registers Logyard configuration and ServiceLoader resources for AOT and native images. */
public final class LogyardRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("logyard.toml");
        hints.resources().registerPattern("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
        hints.resources().registerPattern("META-INF/services/java.lang.System$LoggerFinder");
        hints.resources().registerPattern("META-INF/services/com.zsumz.logyard.api.spi.context.CallerContextProvider");
        hints.resources().registerPattern("META-INF/services/com.zsumz.logyard.api.spi.encoding.EventEncoderProvider");
        hints.resources().registerPattern("META-INF/services/com.zsumz.logyard.api.spi.formatting.TextFormatterProvider");
        hints.resources().registerPattern("META-INF/services/com.zsumz.logyard.api.spi.output.OutputProvider");
        hints.resources().registerPattern("META-INF/services/com.zsumz.logyard.api.spi.processing.EventProcessorProvider");
        hints.proxies().registerJdkProxy(TypeReference.of(
                "org.springframework.boot.actuate.health.HealthIndicator"));
        hints.proxies().registerJdkProxy(TypeReference.of(
                "org.springframework.boot.health.contributor.HealthIndicator"));
        registerHealthReflection(hints, "org.springframework.boot.actuate.health");
        registerHealthReflection(hints, "org.springframework.boot.health.contributor");
    }

    private static void registerHealthReflection(RuntimeHints hints, String packageName) {
        hints.reflection().registerType(
                TypeReference.of(packageName + ".Health"),
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(
                TypeReference.of(packageName + ".Health$Builder"),
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
