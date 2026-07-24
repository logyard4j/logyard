package com.zsumz.logyard.quarkus.runtime.logging;

import org.jboss.logmanager.ExtFormatter;
import org.jboss.logmanager.ExtLogRecord;

/** Uses JBoss Log Manager's supported format-style-aware message rendering path. */
final class QuarkusMessageRenderer {
    private static final ExtFormatter FORMATTER = new ExtFormatter() {
        @Override
        public String format(ExtLogRecord record) {
            return formatMessage(record);
        }
    };

    private QuarkusMessageRenderer() {
    }

    static String render(ExtLogRecord record) {
        return FORMATTER.formatMessage(record);
    }
}
