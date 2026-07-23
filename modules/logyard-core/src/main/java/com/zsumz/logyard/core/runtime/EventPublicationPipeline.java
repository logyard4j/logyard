package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.core.routing.CompiledRoute;

import java.util.Objects;

/** Captures an enabled event, runs its processor chain, and dispatches it to the compiled sink. */
final class EventPublicationPipeline {
    private final PublicationFailureHandler failureHandler;

    EventPublicationPipeline(PublicationFailureHandler failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    void publish(CompiledRoute route, EventDraft draft) {
        if (!route.enables(draft.level())) {
            return;
        }

        LogEvent event = null;
        try {
            event = draft.capture();
            for (EventProcessor processor : route.processors()) {
                event = processor.process(event);
                if (event == null) {
                    return;
                }
            }
            route.sink().accept(event);
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            reportFailure(draft, event, failure);
        }
    }

    private void reportFailure(EventDraft draft, LogEvent event, Throwable failure) {
        try {
            failureHandler.handle(draft, event, failure);
        } catch (Throwable diagnosticFailure) {
            FailureIsolation.prepareForRecovery(diagnosticFailure);
        }
    }
}
