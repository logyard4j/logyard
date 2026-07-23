package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.format.TextTemplate;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Built-in formatter backed by Logyard's bounded, non-executable text-template grammar. */
public final class TemplateTextFormatter implements TextFormatter {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final TextTemplate template;
    private final ZoneId zone;

    public TemplateTextFormatter(String template, ZoneId zone) {
        this(TextTemplate.compile(template), zone);
    }

    public TemplateTextFormatter(TextTemplate template, ZoneId zone) {
        this.template = Objects.requireNonNull(template, "template");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public String format(LogEvent event) {
        Objects.requireNonNull(event, "event");
        return template.render(name -> switch (name) {
            case "timestamp" -> TIMESTAMP.format(
                    Instant.ofEpochMilli(event.timestampMillis()).atZone(zone));
            case "level" -> event.level().name();
            case "logger" -> ConsoleText.sanitize(event.loggerName());
            case "thread" -> ConsoleText.sanitize(event.threadName());
            case "event" -> ConsoleText.sanitize(Objects.requireNonNullElse(event.eventName(), ""));
            case "message" -> ConsoleText.sanitize(event.renderedMessage());
            case "fields" -> fields(event);
            default -> throw new IllegalStateException("unsupported text-template placeholder: " + name);
        });
    }

    private static String fields(LogEvent event) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < event.attributes().size(); index++) {
            if (index > 0) {
                appendBounded(result, " ");
            }
            appendBounded(result, ConsoleText.sanitize(event.attributes().keyAt(index)));
            appendBounded(result, "=");
            appendBounded(result, ConsoleText.safe(event.attributes().valueAt(index)));
            if (result.length() >= CaptureLimits.MAX_TEXT_CHARS) {
                break;
            }
        }
        return result.toString();
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = CaptureLimits.MAX_TEXT_CHARS - target.length();
        if (remaining <= 0) {
            return;
        }
        int end = Math.min(value.length(), remaining);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        target.append(value, 0, end);
    }
}
