package com.zsumz.logyard.api;

import java.util.function.Supplier;

public interface LogBuilder {
    LogBuilder event(String value);
    LogBuilder message(String value);
    LogBuilder argument(Object value);
    LogBuilder argument(Supplier<?> valueSupplier);
    LogBuilder add(String key, Object value);
    LogBuilder add(String key, Supplier<?> valueSupplier);
    LogBuilder cause(Throwable value);
    void log();
    void log(String value);
    void log(String value, Object... values);
}
