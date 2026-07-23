package com.zsumz.logyard.core.runtime;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

final class ActiveLogBuilder implements LogBuilder {
    private final DefaultLogyardLogger logger;
    private final Level level;
    private final List<Object> arguments = new ArrayList<>(4);
    private final AttributeSet.Builder attributes = AttributeSet.builder();
    private String eventName;
    private String messageTemplate;
    private Throwable throwable;
    private int omittedArguments;
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
        Objects.requireNonNull(supplier, "valueSupplier");
        if (arguments.size() < CaptureLimits.MAX_ARGUMENTS) {
            addArgument(supplier.get());
        } else {
            omittedArguments++;
        }
        return this;
    }

    @Override public LogBuilder add(String key, Object value) { attributes.put(key, value); return this; }

    @Override
    public LogBuilder add(String key, Supplier<?> supplier) {
        attributes.putSupplied(key, supplier);
        return this;
    }

    @Override public LogBuilder cause(Throwable value) { throwable = value; return this; }
    @Override public void log() { publish(messageTemplate, arguments.toArray()); }
    @Override public void log(String value) { publish(value, arguments.toArray()); }

    @Override
    public void log(String value, Object... values) {
        Object[] supplied = values == null ? new Object[0] : values;
        if (arguments.isEmpty()) {
            if (supplied.length > CaptureLimits.MAX_ARGUMENTS) {
                omittedArguments += supplied.length - CaptureLimits.MAX_ARGUMENTS;
            }
            publish(value, supplied);
            return;
        }

        int remaining = CaptureLimits.MAX_ARGUMENTS - arguments.size();
        int copied = Math.min(remaining, supplied.length);
        omittedArguments += supplied.length - copied;
        Object[] combined = new Object[arguments.size() + copied];
        for (int index = 0; index < arguments.size(); index++) {
            combined[index] = arguments.get(index);
        }
        System.arraycopy(supplied, 0, combined, arguments.size(), copied);
        publish(value, combined);
    }

    private void addArgument(Object value) {
        if (arguments.size() < CaptureLimits.MAX_ARGUMENTS) {
            arguments.add(value);
        } else {
            omittedArguments++;
        }
    }

    private void publish(String template, Object[] values) {
        if (logged) {
            throw new IllegalStateException("a Logyard LogBuilder can only publish once");
        }
        logged = true;
        if (omittedArguments > 0) {
            attributes.put("logyard.arguments.omitted", omittedArguments);
        }
        logger.publish(level, eventName, template, values, attributes.build(), throwable);
    }
}
