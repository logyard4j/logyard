package com.zsumz.logyard.output.console;

import com.zsumz.logyard.api.event.LogEvent;

@FunctionalInterface
interface ConsoleLineRenderer {
    String render(LogEvent event);
}
