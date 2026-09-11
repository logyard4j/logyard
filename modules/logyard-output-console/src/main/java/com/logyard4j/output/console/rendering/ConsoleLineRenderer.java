package com.logyard4j.output.console.rendering;

import com.logyard4j.api.event.LogEvent;

@FunctionalInterface
interface ConsoleLineRenderer {
    String render(LogEvent event);
}
