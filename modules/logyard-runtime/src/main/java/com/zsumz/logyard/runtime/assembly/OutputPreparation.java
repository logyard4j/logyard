package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.output.EventSink;

import java.util.Objects;

/** One fully wrapped output plus its non-destructive commit action. */
record OutputPreparation(EventSink sink, Runnable activation) {
    OutputPreparation {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(activation, "activation");
    }

    static OutputPreparation active(EventSink sink) {
        return new OutputPreparation(sink, () -> { });
    }

    static OutputPreparation prepared(EventSink sink, Runnable activation) {
        return new OutputPreparation(sink, activation);
    }

    void activate() {
        activation.run();
    }
}
