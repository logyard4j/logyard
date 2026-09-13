package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded positional values whose suppliers remain lazy until event capture. */
final class PendingArguments {
    private final List<Object> values = new ArrayList<>(4);
    private int omitted;

    void add(Object value) {
        store(value);
    }

    void add(Supplier<?> supplier) {
        store(new Supplied(Objects.requireNonNull(supplier, "valueSupplier")));
    }

    void addAll(Object[] supplied) {
        if (supplied == null || supplied.length == 0) {
            return;
        }
        int retained = Math.min(supplied.length, CaptureLimits.MAX_ARGUMENTS - values.size());
        for (int index = 0; index < retained; index++) {
            values.add(supplied[index]);
        }
        addOmitted(supplied.length - retained);
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    int omitted() {
        return omitted;
    }

    int suppliedCount() {
        return (int) Math.min(Integer.MAX_VALUE, (long) values.size() + omitted);
    }

    Object[] capture() {
        Object[] captured = new Object[values.size()];
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            captured[index] = value instanceof Supplied supplied ? supplied.supplier().get() : value;
        }
        return captured;
    }

    void clear() {
        values.clear();
        omitted = 0;
    }

    private void store(Object value) {
        if (values.size() < CaptureLimits.MAX_ARGUMENTS) {
            values.add(value);
        } else {
            addOmitted(1);
        }
    }

    private void addOmitted(int additional) {
        long total = (long) omitted + additional;
        omitted = (int) Math.min(Integer.MAX_VALUE, total);
    }

    private record Supplied(Supplier<?> supplier) {
    }
}
