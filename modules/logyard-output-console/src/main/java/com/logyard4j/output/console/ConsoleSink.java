package com.logyard4j.output.console;

import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.diagnostics.HealthContributor;
import com.logyard4j.api.spi.formatting.TextFormatter;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.output.console.rendering.ConsoleEventRenderer;
import com.logyard4j.output.console.style.ConsoleTheme;
import com.logyard4j.output.console.terminal.ColorCapability;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private volatile StreamPhase phase = StreamPhase.OPEN;
    private volatile String ioOperation = "idle";

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
            ioOperation = "write";
            try {
                lines.forEach(stream::println);
                verifyStream();
            } finally {
                ioOperation = "idle";
            }
        }
    }

    @Override
    public void flush() {
        synchronized (streamState) {
            ensureOpen();
            ioOperation = "flush";
            try {
                stream.flush();
                verifyStream();
            } finally {
                ioOperation = "idle";
            }
        }
    }

    @Override
    public void close() {
        synchronized (streamState) {
            if (phase.closed() || phase.closing()) {
                return;
            }
            boolean alreadyFailed = phase.failed();
            phase = alreadyFailed ? StreamPhase.FAILED_CLOSING : StreamPhase.CLOSING;
            ioOperation = "close";
            try {
                if (closeStream) {
                    stream.close();
                } else {
                    stream.flush();
                }
                if (!alreadyFailed) {
                    verifyStream();
                }
            } finally {
                phase = phase.failed() ? StreamPhase.FAILED_CLOSED : StreamPhase.CLOSED;
                ioOperation = "idle";
            }
        }
    }

    @Override
    public ComponentHealth health(String componentName) {
        StreamPhase snapshot = phase;
        Map<String, String> details = new LinkedHashMap<>(renderer.healthDetails());
        details.put("io_operation", ioOperation);
        return new ComponentHealth(
                componentName,
                "console-output",
                snapshot.failed() ? HealthStatus.FAILED : snapshot.closing() ? HealthStatus.STOPPING
                        : snapshot.closed() ? HealthStatus.STOPPED : HealthStatus.HEALTHY,
                details,
                Map.of());
    }

    private void ensureOpen() {
        if (phase.failed()) {
            throw streamFailure();
        }
        if (phase.closed() || phase.closing()) {
            throw new IllegalStateException("Logyard console output is closed");
        }
    }

    private void verifyStream() {
        if (stream.checkError()) {
            phase = phase.closing() ? StreamPhase.FAILED_CLOSING
                    : phase.closed() ? StreamPhase.FAILED_CLOSED : StreamPhase.FAILED;
            throw streamFailure();
        }
    }

    private static UncheckedIOException streamFailure() {
        return new UncheckedIOException(new IOException("Logyard console stream reported an I/O failure"));
    }

    private enum StreamPhase {
        OPEN,
        FAILED,
        CLOSING,
        FAILED_CLOSING,
        CLOSED,
        FAILED_CLOSED;

        boolean closed() {
            return this == CLOSED || this == FAILED_CLOSED;
        }

        boolean failed() {
            return this == FAILED || this == FAILED_CLOSING || this == FAILED_CLOSED;
        }

        boolean closing() {
            return this == CLOSING || this == FAILED_CLOSING;
        }
    }
}
