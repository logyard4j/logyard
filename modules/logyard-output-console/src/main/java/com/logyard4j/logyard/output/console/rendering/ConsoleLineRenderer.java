package com.logyard4j.logyard.output.console.rendering;

import com.logyard4j.logyard.api.event.LogEvent;

@FunctionalInterface
interface ConsoleLineRenderer {
    String render(LogEvent event);
}
