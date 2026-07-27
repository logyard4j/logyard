package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.LogBuilder;

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
