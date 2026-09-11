package com.logyard4j.spring.boot.internal.actuator;

import com.logyard4j.api.diagnostics.RuntimeHealth;
import com.logyard4j.api.failure.FailureIsolation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Reflective compatibility seam for the package relocation between Spring Boot 3 and 4. */
final class ActuatorHealthApi {
    private static final List<String> PACKAGES = List.of(
            "org.springframework.boot.health.contributor",
            "org.springframework.boot.actuate.health");

    private final Class<?> indicatorType;
    private final Method up;
    private final Method down;
    private final Method withDetail;
    private final Method build;

    private ActuatorHealthApi(Class<?> indicatorType, Class<?> healthType) throws ReflectiveOperationException {
        this.indicatorType = indicatorType;
        up = healthType.getMethod("up");
        down = healthType.getMethod("down");
        Class<?> builderType = up.getReturnType();
        withDetail = builderType.getMethod("withDetail", String.class, Object.class);
        build = builderType.getMethod("build");
    }

    static Optional<ActuatorHealthApi> detect(ClassLoader classLoader) {
        for (String packageName : PACKAGES) {
            try {
                Class<?> indicator = Class.forName(packageName + ".HealthIndicator", false, classLoader);
                Class<?> health = Class.forName(packageName + ".Health", false, classLoader);
                return Optional.of(new ActuatorHealthApi(indicator, health));
            } catch (ClassNotFoundException ignored) {
                // The optional Actuator API for this Boot line is not installed.
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Spring Boot health API is incompatible with Logyard", failure);
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                throw new IllegalStateException("Spring Boot health API discovery failed", failure);
            }
        }
        return Optional.empty();
    }

    Class<?> indicatorType() {
        return indicatorType;
    }

    Object health(RuntimeHealth snapshot) {
        try {
            Object builder = (snapshot.ready() ? up : down).invoke(null);
            withDetail.invoke(builder, "status", snapshot.status().name());
            withDetail.invoke(builder, "ready", snapshot.ready());
            withDetail.invoke(builder, "observedAt", snapshot.observedAt().toString());
            withDetail.invoke(builder, "components", snapshot.components().size());
            return build.invoke(builder);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Spring Boot health contribution failed", failure);
        }
    }
}
