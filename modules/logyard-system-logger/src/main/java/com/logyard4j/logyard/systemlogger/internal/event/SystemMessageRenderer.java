package com.logyard4j.logyard.systemlogger.internal.event;

import com.logyard4j.logyard.api.event.BoundedMessageFormat;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** Resource-bundle lookup and MessageFormat rendering for System.Logger. */
public final class SystemMessageRenderer {
    private SystemMessageRenderer() {
    }

    public static Result render(ResourceBundle bundle, String message, Object[] parameters) {
        String localized = localize(bundle, message);
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(localized, parameters);
        return new Result(result.template(), result.message(), result.formatFailed(), result.truncated());
    }

    private static String localize(ResourceBundle bundle, String message) {
        if (bundle == null || message == null) {
            return message;
        }
        try {
            return bundle.getString(message);
        } catch (MissingResourceException ignored) {
            return message;
        }
    }

    public record Result(String template, String message, boolean formatFailed, boolean truncated) {
    }
}
