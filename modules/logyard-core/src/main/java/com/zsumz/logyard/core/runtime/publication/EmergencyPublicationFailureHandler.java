package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.diagnostics.EmergencyReporter;

/** Bounded standard-error fallback for processor and sink failures. */
final class EmergencyPublicationFailureHandler implements PublicationFailureHandler {
    @Override
    public void handle(EventDraft draft, LogEvent event, Throwable failure) {
        String message = EmergencyText.sanitize(
                event == null ? draft.messageTemplate() : event.renderedMessage(),
                1_024);
        EmergencyReporter.STDERR.report(
                "Logyard delivery failure for " + (event == null ? draft.level() : event.level()) + " "
                        + EmergencyText.sanitize(event == null ? draft.loggerName() : event.loggerName(), 512)
                        + ": " + EmergencyText.failureSummary(failure, 2_048) + " - " + message);
    }
}
