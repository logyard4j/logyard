package com.zsumz.logyard.core.runtime;

import java.util.Objects;
import java.util.function.Supplier;

/** Caller-owned value retained without evaluation until an enabled event is captured. */
final class PendingValue {
    private final Object value;
    private final Supplier<?> supplier;

    private PendingValue(Object value, Supplier<?> supplier) {
        this.value = value;
        this.supplier = supplier;
    }

    static PendingValue direct(Object value) {
        return new PendingValue(value, null);
    }

    static PendingValue supplied(Supplier<?> supplier) {
        return new PendingValue(null, Objects.requireNonNull(supplier, "valueSupplier"));
    }

    Object resolve() {
        return supplier == null ? value : supplier.get();
    }
}
