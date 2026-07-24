package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Bounded positional values whose suppliers remain lazy until event capture. */
final class PendingArguments {
    private final List<PendingValue> values = new ArrayList<>(4);
    private int omitted;

    void add(Object value) {
        add(PendingValue.direct(value));
    }

    void add(Supplier<?> supplier) {
        add(PendingValue.supplied(supplier));
    }

    void addAll(Object[] supplied) {
        if (supplied == null) {
            return;
        }
        for (Object value : supplied) {
            add(value);
        }
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    int omitted() {
        return omitted;
    }

    Object[] capture() {
        Object[] captured = new Object[values.size()];
        for (int index = 0; index < values.size(); index++) {
            captured[index] = values.get(index).resolve();
        }
        return captured;
    }

    private void add(PendingValue value) {
        if (values.size() < CaptureLimits.MAX_ARGUMENTS) {
            values.add(value);
        } else {
            omitted++;
        }
    }
}
