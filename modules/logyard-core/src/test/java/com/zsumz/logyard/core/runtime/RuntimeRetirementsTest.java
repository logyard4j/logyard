package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeRetirementsTest {
    @Test
    void activatesTheReplacementAndRetiresOldOutputsAfterTheLastLease() {
        RecordingSink previousSink = new RecordingSink();
        RecordingSink nextSink = new RecordingSink();
        PlanEpoch previousEpoch = new PlanEpoch();
        RuntimeRetirements retirements = new RuntimeRetirements();
        AtomicBoolean activated = new AtomicBoolean();
        assertTrue(previousEpoch.tryAcquire());

        retirements.replacePlan(plan(previousSink), previousEpoch, plan(nextSink), () -> activated.set(true));

        assertTrue(activated.get());
        assertEquals(1, retirements.pendingCount());
        assertFalse(previousSink.closed.get());

        previousEpoch.release();
        retirements.await(Duration.ofSeconds(1));

        assertTrue(previousSink.closed.get());
        assertFalse(nextSink.closed.get());
        assertEquals(0, retirements.pendingCount());
    }

    @Test
    void cancelsTheReloadReservationWhenPlanActivationFails() {
        RetirementExecutor executor = new RetirementExecutor();
        RuntimeRetirements retirements = new RuntimeRetirements(executor, new SilentRetirementDiagnostics());
        RuntimeException expected = new IllegalStateException("activation failed");

        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> retirements.replacePlan(
                        plan(new RecordingSink()),
                        new PlanEpoch(),
                        plan(new RecordingSink()),
                        () -> {
                            throw expected;
                        }));

        assertSame(expected, failure);
        assertEquals(0, executor.pendingReloads());
    }

    @Test
    void retirementContinuesPastRecoverableOutputCloseErrors() {
        EventSink hostile = new EventSink() {
            @Override
            public void accept(LogEvent event) {
            }

            @Override
            public void close() {
                throw new AssertionError("close failed");
            }
        };
        RecordingSink recording = new RecordingSink();
        RuntimePlan plan = new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("hostile", "recording"), List.of()),
                Map.of(),
                Map.of("hostile", hostile, "recording", recording),
                Map.of(),
                Duration.ofSeconds(1));
        RuntimeRetirements retirements = new RuntimeRetirements();

        retirements.finishPlan(plan, new PlanEpoch());
        retirements.await(Duration.ofSeconds(1));

        assertTrue(recording.closed.get());
        assertEquals(0, retirements.pendingCount());
    }

    private static RuntimePlan plan(EventSink sink) {
        return new RuntimePlan(
                RouteDefinition.root(Level.INFO, List.of("capture"), List.of()),
                Map.of(),
                Map.of("capture", sink),
                Map.of(),
                Duration.ofSeconds(1));
    }

    private static final class RecordingSink implements EventSink {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void accept(LogEvent event) {
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class SilentRetirementDiagnostics implements RuntimeRetirementDiagnostics {
        @Override
        public void retirementFailed(Throwable failure) {
        }

        @Override
        public void shutdownDeadlineElapsed() {
        }
    }
}
