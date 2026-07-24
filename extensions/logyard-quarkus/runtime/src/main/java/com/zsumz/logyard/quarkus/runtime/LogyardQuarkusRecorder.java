package com.zsumz.logyard.quarkus.runtime;

import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.quarkus.runtime.configuration.LogyardQuarkusRuntimeConfig;
import com.zsumz.logyard.quarkus.runtime.configuration.QuarkusConfigurationResolver;
import com.zsumz.logyard.quarkus.runtime.lifecycle.QuarkusRuntimeLifecycle;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

import java.util.Optional;
import java.util.logging.Handler;

/** Runtime-init recorder that owns the Logyard framework lease for a Quarkus application. */
@Recorder
public class LogyardQuarkusRecorder {
    private final RuntimeValue<LogyardQuarkusRuntimeConfig> configuration;

    /**
     * Creates a recorder whose runtime configuration remains deferred until runtime init.
     *
     * @param configuration runtime configuration value supplied by Quarkus
     */
    public LogyardQuarkusRecorder(RuntimeValue<LogyardQuarkusRuntimeConfig> configuration) {
        this.configuration = configuration;
    }

    /**
     * Starts Logyard when enabled and returns the handler consumed by Quarkus logging setup.
     *
     * @param shutdown Quarkus shutdown registry
     * @return optional handler runtime value
     */
    public RuntimeValue<Optional<Handler>> initialize(ShutdownContext shutdown) {
        LogyardQuarkusRuntimeConfig configuration = this.configuration.getValue();
        if (!configuration.enabled()) {
            return new RuntimeValue<>(Optional.empty());
        }
        QuarkusRuntimeLifecycle lifecycle = QuarkusRuntimeLifecycle.start(
                QuarkusConfigurationResolver.resolve(configuration));
        try {
            shutdown.addShutdownTask(lifecycle::close);
        } catch (Throwable registrationFailure) {
            FailureIsolation.prepareForRecovery(registrationFailure);
            closeAfterRegistrationFailure(lifecycle, registrationFailure);
            throw new IllegalStateException("Quarkus rejected the Logyard shutdown task", registrationFailure);
        }
        return new RuntimeValue<>(Optional.of(lifecycle.handler()));
    }

    private static void closeAfterRegistrationFailure(
            QuarkusRuntimeLifecycle lifecycle,
            Throwable registrationFailure) {
        try {
            lifecycle.close();
        } catch (Throwable closeFailure) {
            FailureIsolation.prepareForRecovery(closeFailure);
            if (closeFailure != registrationFailure) {
                registrationFailure.addSuppressed(closeFailure);
            }
        }
    }
}
