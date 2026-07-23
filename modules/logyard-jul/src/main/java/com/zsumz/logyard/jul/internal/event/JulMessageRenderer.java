package com.zsumz.logyard.jul.internal.event;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.LogRecord;

/** Locale-aware JUL message rendering isolated from the runtime event model. */
public final class JulMessageRenderer {
    private JulMessageRenderer() {
    }

    public static Result render(LogRecord record) {
        String pattern = localize(record.getMessage(), record.getResourceBundle());
        Object[] parameters = record.getParameters();
        if (pattern == null || parameters == null || parameters.length == 0) {
            return new Result(pattern, pattern, false);
        }
        try {
            return new Result(pattern, MessageFormat.format(pattern, parameters), false);
        } catch (IllegalArgumentException failure) {
            return new Result(pattern, pattern, true);
        }
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

    public record Result(String template, String message, boolean formatFailed) {
    }
}
