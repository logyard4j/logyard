package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.ingress.IngressMetadata;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.core.routing.CompiledRoute;
import com.logyard4j.core.routing.PlanEpoch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventPublicationPipelineTest {
    @Test
    void runsProcessorsInOrderBeforeDispatch() {
        List<String> steps = new ArrayList<>();
        AtomicReference<LogEvent> delivered = new AtomicReference<>();
        EventProcessor first = event -> {
            steps.add("first");
            return event.withAttributes(AttributeSet.of("first", true));
        };
        EventProcessor second = event -> {
            steps.add("second");
            return event.withAttributes(event.attributes().mergedWith(AttributeSet.of("second", true)));
        };
        CompiledRoute route = new CompiledRoute(
                Level.INFO,
                delivered::set,
                new EventProcessor[] {first, second},
                List.of("test"),
                List.of("first", "second"),
                "root",
                new PlanEpoch());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        new EventPublicationPipeline((draft, event, cause) -> failure.set(cause)).publish(route, draft());

        assertEquals(List.of("first", "second"), steps);
        assertNotNull(delivered.get());
        assertTrue((Boolean) delivered.get().attributes().get("first"));
        assertTrue((Boolean) delivered.get().attributes().get("second"));
        assertNull(failure.get());
    }

    @Test
    void delegatesProcessorFailuresToTheConfiguredStrategy() {
        RuntimeException expected = new IllegalStateException("processor failed");
        AtomicReference<LogEvent> failedEvent = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompiledRoute route = new CompiledRoute(
                Level.INFO,
                ignored -> { },
                new EventProcessor[] {event -> { throw expected; }},
                List.of("test"),
                List.of("failing"),
                "root",
                new PlanEpoch());

        new EventPublicationPipeline((draft, event, cause) -> {
            failedEvent.set(event);
            failure.set(cause);
        }).publish(route, draft());

        assertNotNull(failedEvent.get());
        assertSame(expected, failure.get());
    }

    @Test
    void capturesHostileObjectRenderingWithoutEscapingTheLogCall() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("boom");
            }
        };
        AtomicReference<LogEvent> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompiledRoute route = route(delivered::set, new EventProcessor[0]);

        new EventPublicationPipeline((draft, event, cause) -> failure.set(cause))
                .publish(route, draft(new Object[] {hostile}, null));

        assertNotNull(delivered.get());
        assertEquals("[FAILED toString(): AssertionError]", delivered.get().argumentAt(0));
        assertNull(failure.get());
    }

    @Test
    void capturesHostileThrowableAccessorsWithoutEscapingTheLogCall() {
        RuntimeException hostile = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new AssertionError("boom");
            }
        };
        AtomicReference<LogEvent> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompiledRoute route = route(delivered::set, new EventProcessor[0]);

        new EventPublicationPipeline((draft, event, cause) -> failure.set(cause))
                .publish(route, draft(new Object[0], hostile));

        assertNotNull(delivered.get());
        assertTrue(delivered.get().exception().message().contains("message accessor failed"));
        assertNull(failure.get());
    }

    @Test
    void isolatesAssertionErrorsFromProcessorsAndSinks() {
        AssertionError processorFailure = new AssertionError("processor failed");
        AtomicReference<Throwable> observedProcessorFailure = new AtomicReference<>();
        CompiledRoute processorRoute = route(ignored -> { }, new EventProcessor[] {event -> {
            throw processorFailure;
        }});

        new EventPublicationPipeline((draft, event, cause) -> observedProcessorFailure.set(cause)).publish(processorRoute, draft());

        AssertionError sinkFailure = new AssertionError("sink failed");
        AtomicReference<Throwable> observedSinkFailure = new AtomicReference<>();
        CompiledRoute sinkRoute = route(event -> {
            throw sinkFailure;
        }, new EventProcessor[0]);

        new EventPublicationPipeline((draft, event, cause) -> observedSinkFailure.set(cause)).publish(sinkRoute, draft());

        assertSame(processorFailure, observedProcessorFailure.get());
        assertSame(sinkFailure, observedSinkFailure.get());
    }

    @Test
    void rethrowsFatalJvmFailures() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        CompiledRoute route = route(ignored -> { }, new EventProcessor[] {event -> {
            throw fatal;
        }});
        EventPublicationPipeline pipeline = new EventPublicationPipeline((draft, event, cause) -> { });

        assertSame(fatal, assertThrows(TestVirtualMachineError.class, () -> pipeline.publish(route, draft())));
    }

    @Test
    void restoresInterruptForSneakyCheckedFailures() {
        CompiledRoute route = route(ignored -> { }, new EventProcessor[] {event -> {
            return sneakyThrow(new InterruptedException("interrupted"));
        }});
        try {
            new EventPublicationPipeline((draft, event, cause) -> { }).publish(route, draft());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void isolatesFailuresFromEmergencyDiagnostics() {
        CompiledRoute route = route(event -> {
            throw new AssertionError("sink failed");
        }, new EventProcessor[0]);

        new EventPublicationPipeline((draft, event, cause) -> {
            throw new AssertionError("diagnostics failed");
        }).publish(route, draft());
    }

    private static CompiledRoute route(com.logyard4j.api.spi.output.EventSink sink, EventProcessor[] processors) {
        return new CompiledRoute(
                Level.INFO,
                sink,
                processors,
                List.of("test"),
                List.of(),
                "root",
                new PlanEpoch());
    }

    private static EventDraft draft() {
        return draft(new Object[0], null);
    }

    private static EventDraft draft(Object[] arguments, Throwable throwable) {
        return new EventDraft(
                "test.Logger",
                Level.INFO,
                "test.event",
                "hello {}",
                arguments,
                AttributeSet.EMPTY,
                throwable,
                IngressMetadata.current());
    }

    @SuppressWarnings("unchecked")
    private static <T> T sneakyThrow(Throwable failure) {
        return EventPublicationPipelineTest.<RuntimeException, T>throwUnchecked(failure);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwUnchecked(Throwable failure) throws E {
        throw (E) failure;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private static final long serialVersionUID = 1L;
    }
}
