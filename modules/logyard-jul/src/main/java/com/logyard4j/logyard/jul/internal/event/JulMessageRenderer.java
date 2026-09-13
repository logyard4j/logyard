package com.logyard4j.logyard.jul.internal.event;

import com.logyard4j.logyard.api.event.BoundedMessageFormat;

import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.LogRecord;

/** Locale-aware JUL message rendering isolated from the runtime event model. */
public final class JulMessageRenderer {
    private JulMessageRenderer() {
    }

    public static Result render(LogRecord record) {
        String pattern = localize(record.getMessage(), record.getResourceBundle());
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(pattern, record.getParameters());
        return new Result(result.template(), result.message(), result.formatFailed(), result.truncated());
    }

    private static String localize(String message, ResourceBundle bundle) {
        if (message == null || bundle == null) {
            return message;
        }
        try {
            return bundle.getString(message);
        } catch (MissingResourceException ignored) {
            // JUL specifies that a missing bundle key leaves the original message intact.
            return message;
        }
    }

    public record Result(String template, String message, boolean formatFailed, boolean truncated) {
    }
}
