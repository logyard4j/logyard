package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.core.routing.CompiledRoute;

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
