package com.logyard4j.logyard.quarkus.runtime.logging;

import com.logyard4j.logyard.api.event.BoundedMessageFormat;
import org.jboss.logmanager.ExtLogRecord;

/** Bounded format-style-aware rendering for JBoss Log Manager records. */
final class QuarkusMessageRenderer {
    private QuarkusMessageRenderer() {
    }

    static BoundedMessageFormat.Result render(ExtLogRecord record) {
        String message = localize(record);
        return switch (record.getFormatStyle()) {
            case MESSAGE_FORMAT -> BoundedMessageFormat.messageFormat(message, record.getParameters());
            case PRINTF -> BoundedMessageFormat.printf(message, record.getParameters());
            case NO_FORMAT -> BoundedMessageFormat.literal(message);
        };
    }

    private static String localize(ExtLogRecord record) {
        if (record.getResourceBundle() == null || record.getMessage() == null) {
            return record.getMessage();
        }
        try {
            return record.getResourceBundle().getString(record.getMessage());
        } catch (java.util.MissingResourceException ignored) {
            return record.getMessage();
        }
    }
}
