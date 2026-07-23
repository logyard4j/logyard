package com.zsumz.logyard.output.console.rendering;

import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.formatting.TextFormatter;

import java.util.Objects;

final class FormattedConsoleLineRenderer implements ConsoleLineRenderer {
    private final TextFormatter formatter;

    FormattedConsoleLineRenderer(TextFormatter formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    @Override
    public String render(LogEvent event) {
        String formatted = Objects.requireNonNull(formatter.format(event), "text formatter returned null");
        return CaptureLimits.text(ConsoleText.sanitize(CaptureLimits.text(formatted)));
    }
}
