package com.zsumz.logyard.output.console.rendering;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;
import com.zsumz.logyard.output.console.style.ConsoleTheme;
import com.zsumz.logyard.output.console.terminal.ColorCapability;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Immutable rendering strategy composed from line and exception renderers. */
public final class ConsoleEventRenderer {
    private final ConsoleLineRenderer lineRenderer;
    private final ConsoleExceptionRenderer exceptionRenderer;
    private final Map<String, String> healthDetails;

    private ConsoleEventRenderer(
            ConsoleLineRenderer lineRenderer,
            ConsoleExceptionRenderer exceptionRenderer,
            Map<String, String> healthDetails) {
        this.lineRenderer = lineRenderer;
        this.exceptionRenderer = exceptionRenderer;
        this.healthDetails = Map.copyOf(healthDetails);
    }

    /** Creates the renderer selected by the configured formatter and console policy. */
    public static ConsoleEventRenderer create(
            boolean colors,
            ConsoleTheme theme,
            ColorCapability capability,
            ZoneId zone,
            boolean compactExceptions,
            boolean collapseCommonFrames,
            TextFormatter formatter) {
        ConsoleLineRenderer lineRenderer = formatter == null
                ? new PrettyConsoleLineRenderer(colors, theme, capability, zone)
                : new FormattedConsoleLineRenderer(formatter);
        ConsoleExceptionRenderer exceptionRenderer = new ConsoleExceptionRenderer(
                colors, theme, capability, compactExceptions, collapseCommonFrames);
        Map<String, String> healthDetails = new LinkedHashMap<>();
        healthDetails.put("format", formatter == null ? "pretty" : "custom-text");
        healthDetails.put("theme", theme.name());
        healthDetails.put("color", Boolean.toString(colors));
        healthDetails.put("capability", capability.name().toLowerCase(Locale.ROOT));
        return new ConsoleEventRenderer(lineRenderer, exceptionRenderer, healthDetails);
    }

    /** Renders one event and any captured exception as bounded physical lines. */
    public void render(LogEvent event, Consumer<String> output) {
        ConsoleRenderBudget budget = new ConsoleRenderBudget();
        budget.emit(lineRenderer.render(event), output);
        if (event.exception() != null) {
            exceptionRenderer.render(event.exception(), budget, output);
        }
    }

    /** Returns immutable details describing the selected rendering strategy. */
    public Map<String, String> healthDetails() {
        return healthDetails;
    }
}
