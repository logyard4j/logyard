package com.logyard4j.logyard.core.delivery;

import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.failure.FailureIsolation;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;
import com.logyard4j.logyard.core.failure.ComponentInvocationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Non-owning fan-out; LogyardRuntime owns output lifecycle. */
public final class CompositeSink implements EventSink {
    public static final CompositeSink EMPTY = new CompositeSink(Map.of());
    private final EventSink[] sinks;
    private final String[] acceptComponents;
    private final String[] flushComponents;

    public CompositeSink(List<? extends EventSink> sinks) {
        this(indexed(Objects.requireNonNull(sinks, "sinks")));
    }

    public CompositeSink(Map<String, ? extends EventSink> sinks) {
        Objects.requireNonNull(sinks, "sinks");
        this.sinks = new EventSink[sinks.size()];
        acceptComponents = new String[sinks.size()];
        flushComponents = new String[sinks.size()];
        int index = 0;
        for (Map.Entry<String, ? extends EventSink> entry : sinks.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "sink name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("sink name must not be blank");
            }
            this.sinks[index] = Objects.requireNonNull(entry.getValue(), "sink '" + name + "'");
            acceptComponents[index] = "fanout output '" + name + "' accept";
            flushComponents[index] = "fanout output '" + name + "' flush";
            index++;
        }
    }

    @Override
    public void accept(LogEvent event) {
        ComponentInvocationException failure = null;
        for (int index = 0; index < sinks.length; index++) {
            try {
                sinks[index].accept(event);
            } catch (Throwable current) {
                failure = collect(failure, acceptComponents[index], current);
            }
        }
        throwIfPresent(failure);
    }

    @Override
    public void flush() {
        ComponentInvocationException failure = null;
        for (int index = 0; index < sinks.length; index++) {
            try {
                sinks[index].flush();
            } catch (Throwable current) {
                failure = collect(failure, flushComponents[index], current);
            }
        }
        throwIfPresent(failure);
    }

    private static ComponentInvocationException collect(
            ComponentInvocationException first,
            String component,
            Throwable current) {
        FailureIsolation.prepareForRecovery(current);
        ComponentInvocationException identified = ComponentInvocationBoundary.exception(component, current);
        if (first == null) {
            return identified;
        }
        first.addSuppressed(identified);
        return first;
    }

    private static void throwIfPresent(ComponentInvocationException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private static Map<String, EventSink> indexed(List<? extends EventSink> sinks) {
        Map<String, EventSink> indexed = new LinkedHashMap<>();
        for (int index = 0; index < sinks.size(); index++) {
            indexed.put(Integer.toString(index), Objects.requireNonNull(sinks.get(index), "sink " + index));
        }
        return indexed;
    }
}
