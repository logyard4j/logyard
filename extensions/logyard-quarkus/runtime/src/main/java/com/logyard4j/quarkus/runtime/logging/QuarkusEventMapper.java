package com.logyard4j.quarkus.runtime.logging;

import com.logyard4j.api.Level;
import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.BoundedMessageFormat;
import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.ingress.IngressMetadata;
import com.logyard4j.api.ingress.LogEventIngress;
import com.logyard4j.runtime.context.ContextPolicySnapshot;
import org.jboss.logmanager.ExtLogRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

/** Captures one JBoss Log Manager record at the Logyard ingress boundary. */
final class QuarkusEventMapper {
    private final Supplier<ContextPolicySnapshot> contextPolicySource;

    QuarkusEventMapper() {
        this(ContextPolicySnapshot::all);
    }

    QuarkusEventMapper(Supplier<ContextPolicySnapshot> contextPolicySource) {
        this.contextPolicySource = Objects.requireNonNull(contextPolicySource, "contextPolicySource");
    }

    /** Applies the level mask before wrapping a record; the handler holds its recursion guard. */
    LogEventIngress admit(LogyardRuntime runtime, LogRecord sourceRecord) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(sourceRecord, "record");
        java.util.logging.Level sourceLevel = Objects.requireNonNull(sourceRecord.getLevel(), "record level");
        // JUL gates entirely on the numeric level space, so a custom OFF-valued Level must be
        // suppressed just like the java.util.logging.Level.OFF singleton.
        if (sourceLevel.intValue() == java.util.logging.Level.OFF.intValue()) {
            return null;
        }
        LogEventIngress logger = runtime.logger(loggerName(sourceRecord.getLoggerName()));
        return logger.isEnabled(QuarkusLevelMapper.toLogyard(sourceLevel)) ? logger : null;
    }

    void publish(LogyardRuntime runtime, LogRecord sourceRecord) {
        LogEventIngress logger = admit(runtime, sourceRecord);
        if (logger != null) {
            capture(logger, sourceRecord);
        }
    }

    /**
     * Converts one already admitted record into an event.
     *
     * @param logger       logger returned by {@link #admit(LogyardRuntime, LogRecord)}
     * @param sourceRecord admitted record
     */
    void capture(LogEventIngress logger, LogRecord sourceRecord) {
        ExtLogRecord record = sourceRecord instanceof ExtLogRecord extension
                ? extension
                : ExtLogRecord.wrap(sourceRecord);
        java.util.logging.Level sourceLevel = record.getLevel();
        Level level = QuarkusLevelMapper.toLogyard(sourceLevel);

        BoundedMessageFormat.Result rendered = QuarkusMessageRenderer.render(record);
        AttributeSet.Builder attributes = AttributeSet.builder(18)
                .put("quarkus.level", sourceLevel.getName())
                .put("quarkus.sequence_number", record.getSequenceNumber());
        addSource(attributes, record);
        addProcess(attributes, record);
        addContext(attributes, record, Objects.requireNonNull(contextPolicySource.get(), "context policy snapshot"));
        if (rendered.template() != null && !Objects.equals(rendered.template(), rendered.message())) {
            attributes.put("quarkus.message_template", rendered.template());
        }
        if (record.getResourceBundleName() != null) {
            attributes.put("quarkus.resource_bundle", record.getResourceBundleName());
        }
        if (record.getMarker() != null) {
            attributes.put("quarkus.marker", String.valueOf(record.getMarker()));
        }
        AttributeSet captured = attributes.build();
        if (rendered.formatFailed()) {
            captured = captured.mergedWith(
                    AttributeSet.systemBuilder(1).put("logyard.quarkus.message_format_failed", true).build());
        }
        if (rendered.truncated()) {
            captured = captured.mergedWith(
                    AttributeSet.systemBuilder(1).put("logyard.capture.truncated", true).build());
        }

        Instant instant = record.getInstant();
        logger.log(
                level,
                null,
                rendered.message(),
                null,
                captured,
                record.getThrown(),
                IngressMetadata.source(instant.toEpochMilli(), record.getLongThreadID(), record.getThreadName()));
    }

    private static void addSource(AttributeSet.Builder attributes, ExtLogRecord record) {
        if (record.getSourceClassName() != null) {
            attributes.put("code.namespace", record.getSourceClassName());
        }
        if (record.getSourceMethodName() != null) {
            attributes.put("code.function.name", record.getSourceMethodName());
        }
        if (record.getSourceFileName() != null) {
            attributes.put("code.file.path", record.getSourceFileName());
        }
        if (record.getSourceLineNumber() >= 0) {
            attributes.put("code.line.number", record.getSourceLineNumber());
        }
        if (record.getSourceModuleName() != null) {
            attributes.put("code.module.name", record.getSourceModuleName());
        }
        if (record.getSourceModuleVersion() != null) {
            attributes.put("code.module.version", record.getSourceModuleVersion());
        }
    }

    private static void addProcess(AttributeSet.Builder attributes, ExtLogRecord record) {
        if (record.getHostName() != null) {
            attributes.put("host.name", record.getHostName());
        }
        if (record.getProcessName() != null) {
            attributes.put("process.name", record.getProcessName());
        }
        if (record.getProcessId() >= 0L) {
            attributes.put("process.pid", record.getProcessId());
        }
    }

    private static void addContext(
            AttributeSet.Builder attributes,
            ExtLogRecord record,
            ContextPolicySnapshot policy) {
        if (policy.includesAll()) {
            addAllMdc(attributes, record.getMdcCopy());
        } else if (!policy.disabled()) {
            addSelectedMdc(attributes, record, policy);
        }
        if (record.getNdc() != null && !record.getNdc().isBlank()) {
            attributes.put("quarkus.ndc", record.getNdc());
        }
    }

    private static void addAllMdc(AttributeSet.Builder attributes, Map<String, String> mdc) {
        var entries = mdc.entrySet().iterator();
        int inspected = 0;
        while (entries.hasNext()) {
            if (inspected++ >= CaptureLimits.MAX_ATTRIBUTES || attributes.isFull()) {
                attributes.markTruncated();
                break;
            }
            Map.Entry<String, String> entry = entries.next();
            addMdcEntry(attributes, entry.getKey(), entry.getValue());
        }
    }

    private static void addSelectedMdc(
            AttributeSet.Builder attributes,
            ExtLogRecord record,
            ContextPolicySnapshot policy) {
        for (String key : policy.includedKeys()) {
            String value = record.getMdc(key);
            if (value == null) {
                continue;
            }
            if (attributes.isFull()) {
                attributes.markTruncated();
                break;
            }
            addMdcEntry(attributes, key, value);
        }
    }

    /** MDC keys merge into the application attribute namespace, matching the SLF4J bridge. */
    private static void addMdcEntry(AttributeSet.Builder attributes, String key, String value) {
        if (key == null || key.isBlank() || key.length() > CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS
                || AttributeSet.isReservedKey(key)) {
            attributes.markCaptureTruncated();
            return;
        }
        attributes.put(key, value);
    }

    private static String loggerName(String name) {
        return name == null || name.isBlank() ? "quarkus" : name;
    }
}
