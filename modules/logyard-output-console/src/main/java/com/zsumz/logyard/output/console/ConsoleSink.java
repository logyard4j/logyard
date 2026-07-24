package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.diagnostics.ComponentHealth;
import com.zsumz.logyard.api.diagnostics.HealthStatus;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.diagnostics.HealthContributor;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.output.console.rendering.ConsoleEventRenderer;
import com.zsumz.logyard.output.console.style.ConsoleTheme;
import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.io.PrintStream;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe lifecycle and delivery boundary for semantic console output.
 *
 * <p>Rendering happens outside the stream-state monitor so an extension formatter may call another
 * sink operation without deadlocking. Completed renders are serialized atomically; concurrent
 * events therefore appear in render-completion order, with every event's physical lines contiguous.</p>
 */
public final class ConsoleSink implements EventSink, HealthContributor {
    private final Object streamState = new Object();
    private final PrintStream stream;
    private final boolean closeStream;
    private final ConsoleEventRenderer renderer;
    private volatile boolean closed;

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
    public void accept(LogEvent event) {
        ensureOpen();
        List<String> lines = new ArrayList<>();
        renderer.render(Objects.requireNonNull(event, "event"), lines::add);
        synchronized (streamState) {
            ensureOpen();
            lines.forEach(stream::println);
        }
    }

    @Override
    public void flush() {
        synchronized (streamState) {
            ensureOpen();
            stream.flush();
        }
    }

    @Override
    public void close() {
        synchronized (streamState) {
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
    }

    @Override
    public ComponentHealth health(String componentName) {
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
