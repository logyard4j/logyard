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

import java.util.function.Supplier;

final class NoopLogBuilder implements LogBuilder {
    static final NoopLogBuilder INSTANCE = new NoopLogBuilder();
    private NoopLogBuilder() {
    }
    @Override public LogBuilder event(String value) { return this; }
    @Override public LogBuilder message(String value) { return this; }
    @Override public LogBuilder argument(Object value) { return this; }
    @Override public LogBuilder argument(Supplier<?> valueSupplier) { return this; }
    @Override public LogBuilder add(String key, Object value) { return this; }
    @Override public LogBuilder add(String key, Supplier<?> valueSupplier) { return this; }
    @Override public LogBuilder cause(Throwable value) { return this; }
    @Override public void log() { }
    @Override public void log(String value) { }
    @Override public void log(String value, Object... values) { }
}
