package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.io.PrintStream;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/** Thread-safe lifecycle and delivery boundary for semantic console output. */
public final class ConsoleSink implements EventSink, HealthContributor {
    private final PrintStream stream;
    private final boolean closeStream;
    private final ConsoleEventRenderer renderer;
    private boolean closed;

    public ConsoleSink(PrintStream stream, boolean colors, ConsoleTheme theme) {
        this(stream, colors, theme, ColorCapability.TRUECOLOR, ZoneId.systemDefault(), true, true, false, null);
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
        this(stream, colors, theme, capability, zone, compactExceptions, collapseCommonFrames, closeStream, null);
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
        this.closeStream = closeStream;
        renderer = ConsoleEventRenderer.create(
                colors,
                Objects.requireNonNull(theme, "theme"),
                Objects.requireNonNull(capability, "capability"),
                Objects.requireNonNull(zone, "zone"),
                compactExceptions,
                collapseCommonFrames,
                formatter);
    }

    @Override
    public synchronized void accept(LogEvent event) {
        ensureOpen();
        renderer.render(Objects.requireNonNull(event, "event"), stream::println);
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
        return new ComponentHealth(
                componentName,
                "console-output",
                closed ? HealthStatus.STOPPED : HealthStatus.HEALTHY,
                renderer.healthDetails(),
                Map.of());
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logyard console output is closed");
        }
    }
}
