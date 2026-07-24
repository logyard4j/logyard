package com.zsumz.logyard.quarkus.runtime.health;

import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.RuntimeHealth;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Optional SmallRye Health readiness view of the already-installed Logyard runtime. */
@Readiness
public final class LogyardReadinessCheck implements HealthCheck {
    /** Creates a readiness contributor for CDI registration when SmallRye Health is present. */
    public LogyardReadinessCheck() {
    }

    /**
     * Reports the shared runtime without acquiring another ownership lease.
     *
     * @return MicroProfile Health response
     */
    @Override
    public HealthCheckResponse call() {
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
