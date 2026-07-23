package com.zsumz.logyard.core.delivery;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.core.diagnostics.EmergencyText;

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
        RuntimeException failure = null;
        for (EventSink sink : sinks) {
            try {
                sink.accept(event);
            } catch (RuntimeException current) {
                failure = combine(failure, current);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void flush() {
        RuntimeException failure = null;
        for (EventSink sink : sinks) {
            try {
                sink.flush();
            } catch (RuntimeException current) {
                failure = combine(failure, current);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException current) {
        if (first == null) {
            return current;
        }
        if (first != current) {
            first.addSuppressed(current);
        }
        return first;
    }
}
