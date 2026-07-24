package com.zsumz.logyard.jul.internal.event;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.ingress.LogEventIngress;

import java.time.Instant;
import java.util.Objects;
import java.util.logging.LogRecord;

/** Converts one JUL record without leaking JUL types beyond the ingress boundary. */
public final class JulEventMapper {
    public void publish(LogyardRuntime runtime, LogRecord record) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(record, "record");
        java.util.logging.Level sourceLevel = Objects.requireNonNull(record.getLevel(), "record level");
        if (sourceLevel == java.util.logging.Level.OFF) {
            return;
        }
        Level level = JulLevelMapper.toLogyard(sourceLevel);
        LogEventIngress logger = runtime.logger(loggerName(record));
        if (!logger.isEnabled(level)) {
            return;
        }

        // Resolve JUL's lazy caller metadata before an asynchronous sink can observe the event.
        String sourceClass = record.getSourceClassName();
        String sourceMethod = record.getSourceMethodName();
        JulMessageRenderer.Result rendered = JulMessageRenderer.render(record);
        AttributeSet.Builder attributes = AttributeSet.builder()
                .put("jul.level", sourceLevel.getName())
                .put("jul.sequence_number", record.getSequenceNumber());
        if (rendered.template() != null && !Objects.equals(rendered.template(), rendered.message())) {
            attributes.put("jul.message_template", rendered.template());
        }
        if (sourceClass != null) {
            attributes.put("code.namespace", sourceClass);
        }
        if (sourceMethod != null) {
            attributes.put("code.function.name", sourceMethod);
        }
        if (record.getResourceBundleName() != null) {
            attributes.put("jul.resource_bundle", record.getResourceBundleName());
        }
        AttributeSet captured = attributes.build();
        if (rendered.formatFailed()) {
            captured = captured.mergedWith(
                    AttributeSet.systemBuilder(1).put("logyard.jul.message_format_failed", true).build());
        }
        Instant instant = record.getInstant();
        logger.log(
                level,
                null,
                rendered.message(),
                null,
                captured,
                record.getThrown(),
                IngressMetadata.source(
                        instant.toEpochMilli(),
                        record.getLongThreadID(),
                        null));
    }

    private static String loggerName(LogRecord record) {
        String name = record.getLoggerName();
        return name == null || name.isBlank() ? "java.util.logging" : name;
    }
}
