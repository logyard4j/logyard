package com.logyard4j.spring.boot.internal.provider;

import com.logyard4j.api.failure.FailureIsolation;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/** Rejects ambiguous SLF4J provider selection with exact Spring starter remediation. */
public final class Slf4jProviderGuard {
    private static final String SERVICE = "org.slf4j.spi.SLF4JServiceProvider";
    private static final String LOGYARD_PROVIDER = "com.logyard4j.slf4j.LogyardServiceProvider";

    private Slf4jProviderGuard() {
    }

    public static void requireSoleLogyardProvider(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<String> providers;
        try {
            Class<?> service = Class.forName(SERVICE, false, classLoader);
            providers = ServiceLoader.load(service, classLoader)
                    .stream()
                    .map(provider -> provider.type().getName())
                    .sorted()
                    .toList();
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            throw new IllegalStateException(
                    "Logyard could not inspect SLF4J providers; add com.logyard4j:logyard-slf4j2",
                    failure);
        }
        validateProviders(providers);
    }

    static void validateProviders(List<String> providers) {
        Objects.requireNonNull(providers, "providers");
        if (!providers.equals(List.of(LOGYARD_PROVIDER))) {
            throw new IllegalStateException(
                    "Spring Boot requires Logyard to be the sole SLF4J provider, but found " + providers
                            + ". Keep com.logyard4j:logyard-slf4j2, exclude "
                            + "org.springframework.boot:spring-boot-starter-logging from every Boot starter, "
                            + "and remove other SLF4J providers.");
        }
    }
}
