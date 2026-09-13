package com.logyard4j.logyard.output.console.rendering;

import com.logyard4j.logyard.api.event.CaptureLimits;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.output.console.style.ConsoleTheme;
import com.logyard4j.logyard.output.console.terminal.ColorCapability;

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
        String safeLogger = ConsoleText.sanitize(event.loggerName(), CaptureLimits.MAX_NAME_CHARS * 6);
        String logger = pad(abbreviateLogger(safeLogger, LOGGER_WIDTH), LOGGER_WIDTH);

        ConsoleTextBuffer line = new ConsoleTextBuffer(CaptureLimits.MAX_TEXT_CHARS);
        line.append(theme.role("timestamp").render(timestamp, colors, capability)).append(' ')
                .append(theme.level(event.level()).render(level, colors, capability)).append(' ')
                .append(theme.role("logger").render(logger, colors, capability)).append(' ');
        appendEventName(line, event);
        line.append(ConsoleText.sanitize(event.renderedMessage()));
        appendAttributes(line, event);
        appendThread(line, event);
        return line.finish();
    }

    private void appendEventName(ConsoleTextBuffer line, LogEvent event) {
        if (event.eventName() != null && !event.eventName().isBlank()) {
            line.append(theme.role("event").render('[' + ConsoleText.sanitize(event.eventName()) + ']', colors, capability)).append(' ');
        }
    }

    private void appendAttributes(ConsoleTextBuffer line, LogEvent event) {
        for (int index = 0; index < event.attributes().size() && !line.full(); index++) {
            String key = ConsoleText.sanitize(event.attributes().keyAt(index));
            String value = ConsoleText.safe(event.attributes().valueAt(index), line.remaining());
            line.append(' ')
                    .append(theme.role("field_key").render(key, colors, capability))
                    .append('=')
                    .append(theme.role("field_value").render(value, colors, capability));
        }
    }

    private void appendThread(ConsoleTextBuffer line, LogEvent event) {
        if (!event.threadName().isBlank()) {
            line.append(' ').append(
                    theme.role("thread").render("thread=" + ConsoleText.sanitize(event.threadName()), colors, capability));
        }
    }

    private static String abbreviateLogger(String name, int maximum) {
        if (width(name) <= maximum) {
            return name;
        }
        String[] segments = name.split("\\.", -1);
        StringBuilder result = new StringBuilder(name.length());
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (!segment.isEmpty() && index < segments.length - 1
                    && result.codePointCount(0, result.length()) + width(segment) + 1 > maximum / 2) {
                result.appendCodePoint(segment.codePointAt(0));
            } else {
                result.append(segment);
            }
            if (index < segments.length - 1) {
                result.append('.');
            }
        }
        String abbreviated = result.toString();
        return width(abbreviated) <= maximum ? abbreviated
                : '…' + abbreviated.substring(abbreviated.offsetByCodePoints(abbreviated.length(), 1 - maximum));
    }

    private static String pad(String value, int width) {
        int length = width(value);
        return length >= width ? value : value + " ".repeat(width - length);
    }

    private static int width(String value) {
        return value.codePointCount(0, value.length());
    }
}
