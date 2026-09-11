package com.logyard4j.core.runtime.publication;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogBuilder;
import com.logyard4j.api.event.AttributeSet;

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
    private PublicationPhase phase = PublicationPhase.DRAFT;

    ActiveLogBuilder(DefaultLogyardLogger logger, Level level) {
        this.logger = logger;
        this.level = level;
    }

    @Override public LogBuilder event(String value) { ensureUnpublished(); eventName = value; return this; }
    @Override public LogBuilder message(String value) { ensureUnpublished(); messageTemplate = value; return this; }
    @Override public LogBuilder argument(Object value) { ensureUnpublished(); addArgument(value); return this; }

    @Override
    public LogBuilder argumentLazy(Supplier<?> supplier) {
        ensureUnpublished();
        arguments.add(Objects.requireNonNull(supplier, "valueSupplier"));
        return this;
    }

    @Override public LogBuilder add(String key, Object value) { ensureUnpublished(); attributes.add(key, value); return this; }

    @Override
    public LogBuilder addLazy(String key, Supplier<?> supplier) {
        ensureUnpublished();
        attributes.add(key, supplier);
        return this;
    }

    @Override
    public LogBuilder addAll(AttributeSet values) {
        ensureUnpublished();
        attributes.addAll(values);
        return this;
    }

    @Override public LogBuilder cause(Throwable value) { ensureUnpublished(); throwable = value; return this; }
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
        phase = PublicationPhase.PUBLISHED;
        try {
            logger.publish(level, eventName, template, new PendingEventFields(arguments, attributes), throwable);
        } finally {
            clearCapturedState();
        }
    }

    private void ensureUnpublished() {
        if (phase == PublicationPhase.PUBLISHED) {
            throw new IllegalStateException("a Logyard LogBuilder can only publish once");
        }
    }

    private void clearCapturedState() {
        arguments.clear();
        attributes.clear();
        eventName = null;
        messageTemplate = null;
        throwable = null;
    }

    private enum PublicationPhase {
        DRAFT,
        PUBLISHED
    }
}
