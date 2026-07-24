package com.zsumz.logyard.quarkus.runtime;

import com.zsumz.logyard.quarkus.runtime.configuration.LogyardQuarkusRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogyardQuarkusRecorderTest {
    @Test
    void disabledConfigurationInstallsNothingAndOwnsNothing() {
        RecordingShutdown shutdown = new RecordingShutdown();

        RuntimeValue<Optional<Handler>> result = recorder(false).initialize(shutdown);

        assertTrue(result.getValue().isEmpty());
        assertTrue(shutdown.tasks.isEmpty());
    }

    @Test
    void enabledConfigurationRegistersOneIdempotentShutdownLifecycle() {
        RecordingShutdown shutdown = new RecordingShutdown();

        RuntimeValue<Optional<Handler>> result = recorder(true).initialize(shutdown);

        assertTrue(result.getValue().isPresent());
        assertEquals(1, shutdown.tasks.size());
        shutdown.tasks.getFirst().run();
        shutdown.tasks.getFirst().run();
    }

    private static LogyardQuarkusRuntimeConfig configuration(boolean enabled) {
        return new LogyardQuarkusRuntimeConfig() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public Optional<String> config() {
                return Optional.empty();
            }

            @Override
            public boolean required() {
                return false;
            }
        };
    }

    private static LogyardQuarkusRecorder recorder(boolean enabled) {
        return new LogyardQuarkusRecorder(new RuntimeValue<>(configuration(enabled)));
    }

    private static final class RecordingShutdown implements ShutdownContext {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void addShutdownTask(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void addLastShutdownTask(Runnable task) {
            tasks.add(task);
        }
    }
}
