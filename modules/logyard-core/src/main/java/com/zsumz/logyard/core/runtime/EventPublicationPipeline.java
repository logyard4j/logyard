package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Objects;

/** Captures an enabled event, runs its processor chain, and dispatches it to the compiled sink. */
final class EventPublicationPipeline {
    private final PublicationFailureHandler failureHandler;

    EventPublicationPipeline(PublicationFailureHandler failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void publish(CompiledRoute route, EventDraft draft) {
        if (!route.level().enables(draft.level())) {
            return;
        }

        LogEvent event = draft.capture();
        try {
            for (EventProcessor processor : route.processors()) {
                event = processor.process(event);
                if (event == null) {
                    return;
                }
            }
            route.sink().accept(event);
        } catch (RuntimeException failure) {
            failureHandler.handle(event, failure);
        }
    }
}
