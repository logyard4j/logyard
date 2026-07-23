package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

/** Bounded standard-error fallback for processor and sink failures. */
final class EmergencyPublicationFailureHandler implements PublicationFailureHandler {
    @Override
    public void handle(EventDraft draft, LogEvent event, Throwable failure) {
        String message = EmergencyText.sanitize(
                event == null ? draft.messageTemplate() : event.renderedMessage(),
                CaptureLimits.MAX_TEXT_CHARS);
        System.err.println(
                "Logyard delivery failure for " + (event == null ? draft.level() : event.level()) + " "
                        + EmergencyText.sanitize(event == null ? draft.loggerName() : event.loggerName(), CaptureLimits.MAX_NAME_CHARS)
                        + " - " + message + ": " + EmergencyText.failureSummary(failure, 4_096));
    }
}
