package com.zsumz.logyard.core.delivery.async;

import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

import java.time.Instant;

/** Bounded standard-error diagnostics for failures that cannot safely use the normal output path. */
final class AsyncSinkDiagnostics {
    private final String outputName;

    AsyncSinkDiagnostics(String outputName) {
        this.outputName = outputName;
    }

    void status(String message) {
        System.err.println("Logyard async output '" + EmergencyText.sanitize(outputName, 256) + "': " + EmergencyText.sanitize(message, 4_096));
    }

    void failure(String component, Throwable failure) {
        status(component + " failure: " + EmergencyText.failureSummary(failure, 2_048));
    }

    void emergency(LogEvent event, String reason) {
        System.err.printf(
                "%s %-5s %s - %s [Logyard emergency path: %s]%n",
                Instant.ofEpochMilli(event.timestampMillis()),
                event.level(),
                EmergencyText.sanitize(event.loggerName(), 1_024),
                EmergencyText.sanitize(event.renderedMessage(), 65_536),
                EmergencyText.sanitize(reason, 2_048));
        if (event.exception() != null) {
            printException(event.exception(), 0);
        }
    }

    private static void printException(ExceptionSnapshot exception, int depth) {
        if (depth > ExceptionSnapshot.MAX_CAUSE_DEPTH) {
            return;
        }
        String prefix = depth == 0 ? "" : "Caused by: ";
        System.err.println(prefix + EmergencyText.sanitize(exception.summary(), 16_384));
        int frameCount = Math.min(exception.frames().size(), 32);
        for (int index = 0; index < frameCount; index++) {
            System.err.println("    at " + EmergencyText.sanitize(exception.frames().get(index).toString(), 2_048));
        }
        if (exception.frames().size() > frameCount || exception.truncated()) {
            System.err.println("    ... exception snapshot bounded");
        }
        if (exception.cause() != null) {
            printException(exception.cause(), depth + 1);
        }
    }
}
