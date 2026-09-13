package com.logyard4j.logyard.spring.boot.nativeimage;

import com.logyard4j.logyard.api.spi.context.ContextProvider;
import com.logyard4j.logyard.api.spi.encoding.EventEncoderProvider;
import com.logyard4j.logyard.api.spi.formatting.TextFormatterProvider;
import com.logyard4j.logyard.api.spi.output.OutputProvider;
import com.logyard4j.logyard.api.spi.processing.EventProcessorProvider;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/** Registers Logyard configuration and ServiceLoader resources for AOT and native images. */
public final class LogyardRuntimeHints implements RuntimeHintsRegistrar {
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("logyard.toml");
        hints.resources().registerPattern("logyard-*.toml");
        hints.resources().registerPattern("**/logyard.toml");
        hints.resources().registerPattern("**/logyard-*.toml");
        hints.resources().registerPattern("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
        registerServiceResource(hints, System.LoggerFinder.class);
        registerServiceResource(hints, ContextProvider.class);
        registerServiceResource(hints, EventEncoderProvider.class);
        registerServiceResource(hints, TextFormatterProvider.class);
        registerServiceResource(hints, OutputProvider.class);
        registerServiceResource(hints, EventProcessorProvider.class);
        hints.proxies().registerJdkProxy(TypeReference.of(
                "org.springframework.boot.actuate.health.HealthIndicator"));
        hints.proxies().registerJdkProxy(TypeReference.of(
                "org.springframework.boot.health.contributor.HealthIndicator"));
        registerHealthReflection(hints, "org.springframework.boot.actuate.health");
        registerHealthReflection(hints, "org.springframework.boot.health.contributor");
    }

    private static void registerServiceResource(RuntimeHints hints, Class<?> serviceType) {
        hints.resources().registerPattern("META-INF/services/" + serviceType.getName());
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
