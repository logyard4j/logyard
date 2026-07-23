package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.Logyard;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.core.delivery.CompositeSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.routing.CompiledRoute;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.core.routing.PlanEpoch;
import com.zsumz.logyard.core.routing.RouteDefinition;

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
        enabledMask = Level.enabledMaskFrom(next.level());
    }
}
