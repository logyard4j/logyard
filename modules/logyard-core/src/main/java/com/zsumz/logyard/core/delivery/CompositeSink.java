package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.failure.FailureIsolation;
import com.zsumz.logyard.api.spi.output.EventSink;
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
        Throwable failure = null;
        for (EventSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (Throwable current) {
                failure = collect(failure, current);
            }
        }
        throwIfPresent("fanout accept", failure);
    }

    @Override
    public void flush() {
        Throwable failure = null;
        for (EventSink sink : sinks) {
            try {
                sink.flush();
            } catch (Throwable current) {
                failure = collect(failure, current);
            }
        }
        throwIfPresent("fanout flush", failure);
    }

    private static Throwable collect(Throwable first, Throwable current) {
        FailureIsolation.prepareForRecovery(current);
        if (first == null) {
            return current;
        }
        if (first != current) {
            first.addSuppressed(current);
        }
        return first;
    }

    private static void throwIfPresent(String operation, Throwable failure) {
        if (failure != null) {
            throw ComponentInvocationBoundary.exception(operation, failure);
        }
    }
}
