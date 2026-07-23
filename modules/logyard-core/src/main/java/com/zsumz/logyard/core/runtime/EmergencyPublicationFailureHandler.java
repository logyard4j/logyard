package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

/** Bounded standard-error fallback for processor and sink failures. */
final class EmergencyPublicationFailureHandler implements PublicationFailureHandler {
    @Override
    public void handle(LogEvent event, RuntimeException failure) {
        String message = EmergencyText.sanitize(event.renderedMessage(), CaptureLimits.MAX_TEXT_CHARS);
        System.err.println(
                "Logyard delivery failure for " + event.level() + " "
                        + EmergencyText.sanitize(event.loggerName(), CaptureLimits.MAX_NAME_CHARS)
                        + " - " + message + ": " + EmergencyText.failureSummary(failure, 4_096));
    }
}
