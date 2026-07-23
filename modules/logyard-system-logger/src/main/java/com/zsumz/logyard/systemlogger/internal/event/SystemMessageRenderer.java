package com.zsumz.logyard.systemlogger.internal.event;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** Resource-bundle lookup and MessageFormat rendering for System.Logger. */
public final class SystemMessageRenderer {
    private SystemMessageRenderer() {
    }

    public static Result render(ResourceBundle bundle, String message, Object[] parameters) {
        String localized = localize(bundle, message);
        if (localized == null || parameters == null || parameters.length == 0) {
            return new Result(localized, localized, false);
        }
        try {
            return new Result(localized, MessageFormat.format(localized, parameters), false);
        } catch (IllegalArgumentException failure) {
            return new Result(localized, localized, true);
        }
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

    public record Result(String template, String message, boolean formatFailed) {
    }
}
