package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

final class ConsoleEventRenderer {
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

    static ConsoleEventRenderer create(
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

    void render(LogEvent event, Consumer<String> output) {
        output.accept(lineRenderer.render(event));
        if (event.exception() != null) {
            exceptionRenderer.render(event.exception(), output);
        }
    }

    Map<String, String> healthDetails() {
        return healthDetails;
    }
}
