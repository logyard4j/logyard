package com.zsumz.logyard.core.runtime.publication;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogBuilder;
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.core.runtime.publication.RuntimePublication.EventPublisher;

import java.util.Objects;

final class DefaultLogyardLogger implements LogyardLogger {
    private final String name;
    private final EventPublisher publisher;
    private final LoggerControl control;

    DefaultLogyardLogger(String name, EventPublisher publisher, LoggerControl control) {
        this.name = name;
        this.publisher = publisher;
        this.control = control;
    }

    @Override public String name() { return name; }
    @Override public boolean isEnabled(Level level) { return control.enabled(Objects.requireNonNull(level, "level")); }

    @Override
    public LogBuilder at(Level level) {
        return isEnabled(level) ? new ActiveLogBuilder(this, level) : NoopLogBuilder.INSTANCE;
    }

    /** Adapter entry point used by SLF4J and future ingress modules. */
    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable) {
        log(
                level,
                eventName,
                messageTemplate,
                arguments,
                attributes,
                throwable,
                IngressMetadata.current());
    }

    @Override
    public void log(
            Level level,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        if (isEnabled(level)) {
            publish(level, eventName, messageTemplate, arguments, attributes, throwable, metadata);
        }
    }

    void publish(
            Level level,
            String eventName,
            String template,
            PendingEventFields fields,
            Throwable throwable) {
        EventDraft draft = new EventDraft(
                name,
                level,
                eventName,
                template,
                fields,
                throwable,
                IngressMetadata.current());
        publisher.publish(control, draft);
    }

    private void publish(
            Level level,
            String eventName,
            String template,
            Object[] arguments,
            AttributeSet attributes,
            Throwable throwable,
            IngressMetadata metadata) {
        EventDraft draft = new EventDraft(
                name,
                level,
                eventName,
                template,
                arguments,
                attributes,
                throwable,
                Objects.requireNonNull(metadata, "metadata"));
        publisher.publish(control, draft);
    }

}
