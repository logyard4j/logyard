package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.failure.ComponentFailureCollector;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;

import java.util.List;
import java.util.Objects;

/** Non-owning fan-out; LogyardRuntime owns output lifecycle. */
public final class CompositeSink implements EventSink {
    public static final CompositeSink EMPTY = new CompositeSink(List.of());
    private final EventSink[] sinks;

    public CompositeSink(List<? extends EventSink> sinks) {
        Objects.requireNonNull(sinks, "sinks");
        this.sinks = new EventSink[sinks.size()];
        for (int index = 0; index < sinks.size(); index++) {
            this.sinks[index] = Objects.requireNonNull(sinks.get(index), "sink " + index);
        }
    }

    @Override
    public void accept(LogEvent event) {
        ComponentFailureCollector failures = new ComponentFailureCollector();
        for (int index = 0; index < sinks.length; index++) {
            EventSink sink = sinks[index];
            ComponentInvocationBoundary.invoke(
                    "fanout sink " + index + " accept",
                    () -> sink.accept(event),
                    failures);
        }
        failures.throwIfPresent("fanout accept");
    }

    @Override
    public void flush() {
        ComponentFailureCollector failures = new ComponentFailureCollector();
        for (int index = 0; index < sinks.length; index++) {
            EventSink sink = sinks[index];
            ComponentInvocationBoundary.invoke(
                    "fanout sink " + index + " flush",
                    sink::flush,
                    failures);
        }
        failures.throwIfPresent("fanout flush");
    }
}
