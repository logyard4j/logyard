package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        new EventPublicationPipeline((event, cause) -> failure.set(cause)).publish(route, draft());

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
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CompiledRoute route = new CompiledRoute(
                Level.INFO,
                ignored -> { },
                new EventProcessor[] {event -> { throw expected; }},
                List.of("test"),
                List.of("failing"),
                "root",
                new PlanEpoch());

        new EventPublicationPipeline((event, cause) -> {
            failedEvent.set(event);
            failure.set(cause);
        }).publish(route, draft());

        assertNotNull(failedEvent.get());
        assertSame(expected, failure.get());
    }

    private static EventDraft draft() {
        return new EventDraft(
                "test.Logger",
                Level.INFO,
                "test.event",
                "hello",
                new Object[0],
                AttributeSet.EMPTY,
                null,
                IngressMetadata.current());
    }
}
