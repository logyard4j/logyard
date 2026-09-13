package com.logyard4j.logyard.core.failure;

/** Component callback that returns a value and may use a checked or unchecked failure channel. */
@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Throwable;
}
