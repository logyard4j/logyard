package com.logyard4j.test;

import com.logyard4j.api.event.LogEvent;
import com.logyard4j.core.diagnostics.EmergencyText;

import java.util.List;

/** Renders a bounded one-line-per-event dump so a failed expectation is diagnosable from its message. */
final class CapturedEventDump {
    /** Maximum events listed in one dump. */
    static final int MAX_DUMPED_EVENTS = 20;

    /** Maximum UTF-16 characters of a rendered message retained per line. */
    static final int MAX_MESSAGE_CHARS = 120;


    private CapturedEventDump() {
    }

    static String render(List<LogEvent> events) {
        StringBuilder dump = new StringBuilder(128);
        dump.append("captured ").append(events.size()).append(" event(s)");
        if (events.isEmpty()) {
            return dump.append(':').toString();
        }
        int listed = Math.min(events.size(), MAX_DUMPED_EVENTS);
        dump.append(", showing ").append(listed).append(':');
        for (int index = 0; index < listed; index++) {
            dump.append(System.lineSeparator());
            appendEvent(dump, index, events.get(index));
        }
        int hidden = events.size() - listed;
        if (hidden > 0) {
            dump.append(System.lineSeparator())
                    .append("  ... ").append(hidden).append(" further event(s) not shown");
        }
        return dump.toString();
    }

    private static void appendEvent(StringBuilder dump, int index, LogEvent event) {
        dump.append("  [").append(index).append("] ")
                .append(event.level())
                .append(' ').append(text(event.loggerName()));
        if (event.eventName() != null) {
            dump.append(" event=").append(text(event.eventName()));
        }
        dump.append(" message=\"").append(EmergencyText.sanitize(event.renderedMessage(), MAX_MESSAGE_CHARS)).append('"')
                .append(" attributes=").append(text(event.attributes().toMap().keySet().toString()));
        if (event.exception() != null) {
            dump.append(" exception=").append(text(event.exception().type()));
        }
    }

    static String text(String value) {
        return EmergencyText.sanitize(value, 256);
    }
}
