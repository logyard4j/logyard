package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;

import java.util.Objects;
import java.util.function.Supplier;

final class ActiveLogBuilder implements LogBuilder {
    private final DefaultLogyardLogger logger;
    private final Level level;
    private final PendingArguments arguments = new PendingArguments();
    private final PendingAttributes attributes = new PendingAttributes();
    private String eventName;
    private String messageTemplate;
    private Throwable throwable;
    private boolean logged;

    ActiveLogBuilder(DefaultLogyardLogger logger, Level level) {
        this.logger = logger;
        this.level = level;
    }

    @Override public LogBuilder event(String value) { eventName = value; return this; }
    @Override public LogBuilder message(String value) { messageTemplate = value; return this; }
    @Override public LogBuilder argument(Object value) { addArgument(value); return this; }

    @Override
    public LogBuilder argument(Supplier<?> supplier) {
        arguments.add(Objects.requireNonNull(supplier, "valueSupplier"));
        return this;
    }

    @Override public LogBuilder add(String key, Object value) { attributes.add(key, value); return this; }

    @Override
    public LogBuilder add(String key, Supplier<?> supplier) {
        attributes.add(key, supplier);
        return this;
    }

    @Override public LogBuilder cause(Throwable value) { throwable = value; return this; }
    @Override public void log() { publish(messageTemplate); }
    @Override public void log(String value) { publish(value); }

    @Override
    public void log(String value, Object... values) {
        ensureUnpublished();
        arguments.addAll(values);
        publish(value);
    }

    private void addArgument(Object value) {
        arguments.add(value);
    }

    private void publish(String template) {
        ensureUnpublished();
        logged = true;
        logger.publish(level, eventName, template, new PendingEventFields(arguments, attributes), throwable);
    }

    private void ensureUnpublished() {
        if (logged) {
            throw new IllegalStateException("a Logyard LogBuilder can only publish once");
        }
    }
}
