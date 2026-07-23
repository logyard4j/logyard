package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.HealthContributor;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.event.CaptureLimits;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compact semantic console output. It never emits control bytes from user-owned data. */
public final class ConsoleSink implements EventSink, HealthContributor {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int DEFAULT_LOGGER_WIDTH = 34;
    private static final int COMPACT_STACK_FRAMES = 8;
    private static final int FULL_STACK_FRAMES = 64;

    private final PrintStream stream;
    private final boolean colors;
    private final ConsoleTheme theme;
    private final ColorCapability capability;
    private final ZoneId zone;
    private final boolean compactExceptions;
    private final boolean collapseCommonFrames;
    private final boolean closeStream;
    private final TextFormatter formatter;
    private boolean closed;

    public ConsoleSink(PrintStream stream, boolean colors, ConsoleTheme theme) {
        this(stream, colors, theme, ColorCapability.TRUECOLOR, ZoneId.systemDefault(),
                true, true, false, null);
    }

    public ConsoleSink(
            PrintStream stream,
            boolean colors,
            ConsoleTheme theme,
            ColorCapability capability,
            ZoneId zone,
            boolean compactExceptions,
            boolean closeStream) {
        this(stream, colors, theme, capability, zone, compactExceptions, true, closeStream, null);
    }

    public ConsoleSink(
            PrintStream stream,
            boolean colors,
            ConsoleTheme theme,
            ColorCapability capability,
            ZoneId zone,
            boolean compactExceptions,
            boolean collapseCommonFrames,
            boolean closeStream) {
        this(stream, colors, theme, capability, zone, compactExceptions,
                collapseCommonFrames, closeStream, null);
    }

    public ConsoleSink(
            PrintStream stream,
            boolean colors,
            ConsoleTheme theme,
            ColorCapability capability,
            ZoneId zone,
            boolean compactExceptions,
            boolean collapseCommonFrames,
            boolean closeStream,
            TextFormatter formatter) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.colors = colors;
        this.theme = Objects.requireNonNull(theme, "theme");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.compactExceptions = compactExceptions;
        this.collapseCommonFrames = collapseCommonFrames;
        this.closeStream = closeStream;
        this.formatter = formatter;
    }

    @Override
    public synchronized void accept(LogEvent event) {
        ensureOpen();
        Objects.requireNonNull(event, "event");
        if (formatter != null) {
            String formatted = Objects.requireNonNull(
                    formatter.format(event), "text formatter returned null");
            stream.println(CaptureLimits.text(ConsoleText.sanitize(CaptureLimits.text(formatted))));
        } else {
            String timestamp = TIME.format(Instant.ofEpochMilli(event.timestampMillis()).atZone(zone));
            String level = pad(event.level().name(), 5);
            String logger = pad(
                    abbreviateLogger(event.loggerName(), DEFAULT_LOGGER_WIDTH),
                    DEFAULT_LOGGER_WIDTH);

            StringBuilder line = new StringBuilder(192);
            line.append(theme.role("timestamp").render(timestamp, colors, capability)).append(' ')
                    .append(theme.level(event.level()).render(level, colors, capability)).append(' ')
                    .append(theme.role("logger").render(logger, colors, capability)).append(' ');
            if (event.eventName() != null && !event.eventName().isBlank()) {
                line.append(theme.role("event").render(
                                '[' + ConsoleText.sanitize(event.eventName()) + ']',
                                colors,
                                capability))
                        .append(' ');
            }
            line.append(ConsoleText.sanitize(event.renderedMessage()));
            for (int index = 0; index < event.attributes().size(); index++) {
                String key = ConsoleText.sanitize(event.attributes().keyAt(index));
                String value = ConsoleText.safe(event.attributes().valueAt(index));
                line.append(' ')
                        .append(theme.role("field_key").render(key, colors, capability))
                        .append('=')
                        .append(theme.role("field_value").render(value, colors, capability));
            }
            if (!event.threadName().isBlank()) {
                line.append(' ').append(theme.role("thread").render(
                        "thread=" + ConsoleText.sanitize(event.threadName()), colors, capability));
            }
            stream.println(line);
        }
        if (event.exception() != null) {
            renderException(event.exception(), 0, null, false);
        }
    }

    @Override
    public synchronized void flush() {
        ensureOpen();
        stream.flush();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (closeStream) {
            stream.close();
        } else {
            stream.flush();
        }
    }

    @Override
    public synchronized ComponentHealth health(String componentName) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("format", formatter == null ? "pretty" : "custom-text");
        details.put("theme", theme.name());
        details.put("color", Boolean.toString(colors));
        details.put("capability", capability.name().toLowerCase(java.util.Locale.ROOT));
        return new ComponentHealth(
                componentName,
                "console-output",
                closed ? HealthStatus.STOPPED : HealthStatus.HEALTHY,
                details,
                Map.of());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard console output is closed");
        }
    }

    private void renderException(
            ExceptionSnapshot exception,
            int depth,
            List<StackTraceElement> parentFrames,
            boolean suppressed) {
        String prefix;
        if (depth == 0) {
            prefix = "  ";
        } else if (suppressed) {
            prefix = "  Suppressed: ";
        } else {
            prefix = "  Caused by: ";
        }
        String heading = prefix + ConsoleText.sanitize(exception.summary());
        stream.println(theme.role("exception").render(heading, colors, capability));

        List<StackTraceElement> frames = exception.frames();
        int common = parentFrames == null || !collapseCommonFrames
                ? 0
                : commonFrames(frames, parentFrames);
        int available = frames.size() - common;
        int limit = Math.min(available, compactExceptions ? COMPACT_STACK_FRAMES : FULL_STACK_FRAMES);
        for (int index = 0; index < limit; index++) {
            stream.println(theme.role("stack_frame").render(
                    "    at " + ConsoleText.sanitize(frames.get(index).toString()), colors, capability));
        }
        if (available > limit) {
            stream.println(theme.role("stack_frame").render(
                    "    ... " + (available - limit) + " frame(s) truncated", colors, capability));
        }
        if (common > 0) {
            stream.println(theme.role("stack_frame").render(
                    "    ... " + common + " common frame(s)", colors, capability));
        }
        if (exception.truncated()) {
            stream.println(theme.role("stack_frame").render(
                    "    ... exception snapshot was bounded", colors, capability));
        }
        for (ExceptionSnapshot current : exception.suppressed()) {
            renderException(current, depth + 1, frames, true);
        }
        if (exception.cause() != null) {
            renderException(exception.cause(), depth + 1, frames, false);
        }
    }

    private static int commonFrames(List<StackTraceElement> left, List<StackTraceElement> right) {
        int leftIndex = left.size() - 1;
        int rightIndex = right.size() - 1;
        int count = 0;
        while (leftIndex >= 0 && rightIndex >= 0 && left.get(leftIndex).equals(right.get(rightIndex))) {
            leftIndex--;
            rightIndex--;
            count++;
        }
        return count;
    }

    private static String abbreviateLogger(String name, int maximum) {
        if (name.length() <= maximum) {
            return name;
        }
        String[] segments = name.split("\\.");
        StringBuilder result = new StringBuilder(name.length());
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (index < segments.length - 1 && result.length() + segment.length() + 1 > maximum / 2) {
                result.append(segment.charAt(0));
            } else {
                result.append(segment);
            }
            if (index < segments.length - 1) {
                result.append('.');
            }
        }
        String abbreviated = result.toString();
        if (abbreviated.length() <= maximum) {
            return abbreviated;
        }
        return '…' + abbreviated.substring(abbreviated.length() - maximum + 1);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
