package com.zsumz.logyard.output.console.rendering;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.output.console.style.ConsoleTheme;
import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

final class PrettyConsoleLineRenderer implements ConsoleLineRenderer {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int LOGGER_WIDTH = 34;

    private final boolean colors;
    private final ConsoleTheme theme;
    private final ColorCapability capability;
    private final ZoneId zone;

    PrettyConsoleLineRenderer(boolean colors, ConsoleTheme theme, ColorCapability capability, ZoneId zone) {
        this.colors = colors;
        this.theme = Objects.requireNonNull(theme, "theme");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public String render(LogEvent event) {
        String timestamp = TIME.format(Instant.ofEpochMilli(event.timestampMillis()).atZone(zone));
        String level = pad(event.level().name(), 5);
        String logger = pad(abbreviateLogger(event.loggerName(), LOGGER_WIDTH), LOGGER_WIDTH);

        StringBuilder line = new StringBuilder(192);
        line.append(theme.role("timestamp").render(timestamp, colors, capability)).append(' ')
                .append(theme.level(event.level()).render(level, colors, capability)).append(' ')
                .append(theme.role("logger").render(logger, colors, capability)).append(' ');
        appendEventName(line, event);
        line.append(ConsoleText.sanitize(event.renderedMessage()));
        appendAttributes(line, event);
        appendThread(line, event);
        return line.toString();
    }

    private void appendEventName(StringBuilder line, LogEvent event) {
        if (event.eventName() != null && !event.eventName().isBlank()) {
            line.append(theme.role("event").render('[' + ConsoleText.sanitize(event.eventName()) + ']', colors, capability)).append(' ');
        }
    }

    private void appendAttributes(StringBuilder line, LogEvent event) {
        for (int index = 0; index < event.attributes().size(); index++) {
            String key = ConsoleText.sanitize(event.attributes().keyAt(index));
            String value = ConsoleText.safe(event.attributes().valueAt(index));
            line.append(' ')
                    .append(theme.role("field_key").render(key, colors, capability))
                    .append('=')
                    .append(theme.role("field_value").render(value, colors, capability));
        }
    }

    private void appendThread(StringBuilder line, LogEvent event) {
        if (!event.threadName().isBlank()) {
            line.append(' ').append(
                    theme.role("thread").render("thread=" + ConsoleText.sanitize(event.threadName()), colors, capability));
        }
    }

    private static String abbreviateLogger(String name, int maximum) {
        if (name.length() <= maximum) {
            return name;
        }
        String[] segments = name.split("\\.");
        StringBuilder result = new StringBuilder(name.length());
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            result.append(index < segments.length - 1 && result.length() + segment.length() + 1 > maximum / 2
                    ? segment.charAt(0)
                    : segment);
            if (index < segments.length - 1) {
                result.append('.');
            }
        }
        String abbreviated = result.toString();
        return abbreviated.length() <= maximum ? abbreviated : '…' + abbreviated.substring(abbreviated.length() - maximum + 1);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
