package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.Level;
import com.logyard4j.core.routing.CompiledRoute;

final class LoggerControl {
    volatile CompiledRoute route;
    volatile int enabledMask;

    LoggerControl(CompiledRoute route) {
        update(route);
    }

    boolean enabled(Level level) {
        return (enabledMask & level.mask()) != 0;
    }

    void update(CompiledRoute next) {
        route = next;
        enabledMask = next.enabledMask();
    }
}
