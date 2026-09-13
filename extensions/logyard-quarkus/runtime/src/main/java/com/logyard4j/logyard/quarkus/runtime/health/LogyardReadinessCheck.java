package com.logyard4j.logyard.quarkus.runtime.health;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.api.LogyardRuntime;
import com.logyard4j.logyard.api.diagnostics.RuntimeHealth;
import com.logyard4j.logyard.quarkus.runtime.configuration.LogyardQuarkusRuntimeConfig;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Optional SmallRye Health readiness view of the already-installed Logyard runtime. */
@Readiness
public final class LogyardReadinessCheck implements HealthCheck {
    private final LogyardQuarkusRuntimeConfig configuration;

    /**
     * Creates a readiness contributor for CDI registration when SmallRye Health is present.
     *
     * @param configuration extension runtime configuration
     */
    @Inject
    public LogyardReadinessCheck(LogyardQuarkusRuntimeConfig configuration) {
        this.configuration = configuration;
    }

    /**
     * Reports the shared runtime without acquiring another ownership lease.
     *
     * @return MicroProfile Health response
     */
    @Override
    public HealthCheckResponse call() {
        if (!configuration.enabled()) {
            return HealthCheckResponse.named("logyard")
                    .up()
                    .withData("status", "DISABLED")
                    .build();
        }
        LogyardRuntime runtime = Logyard.runtimeOrNull();
        if (runtime == null) {
            return HealthCheckResponse.named("logyard")
                    .down()
                    .withData("status", "NOT_INSTALLED")
                    .build();
        }
        RuntimeHealth health = runtime.health();
        return HealthCheckResponse.named("logyard")
                .status(health.ready())
                .withData("status", health.status().name())
                .withData("components", health.components().size())
                .build();
    }
}
