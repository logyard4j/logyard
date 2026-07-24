package com.zsumz.logyard.quarkus.runtime.logging;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.ingress.IngressMetadata;
import com.zsumz.logyard.api.ingress.LogEventIngress;
import com.zsumz.logyard.runtime.context.ContextPolicySnapshot;
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

    void publish(LogyardRuntime runtime, LogRecord sourceRecord) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(sourceRecord, "record");
        ExtLogRecord record = sourceRecord instanceof ExtLogRecord extension
                ? extension
                : ExtLogRecord.wrap(sourceRecord);
        java.util.logging.Level sourceLevel = Objects.requireNonNull(record.getLevel(), "record level");
        if (sourceLevel == java.util.logging.Level.OFF) {
            return;
        }

        Level level = QuarkusLevelMapper.toLogyard(sourceLevel);
        LogEventIngress logger = runtime.logger(loggerName(record));
        if (!logger.isEnabled(level)) {
            return;
        }

        String template = record.getMessage();
        String rendered = QuarkusMessageRenderer.render(record);
        AttributeSet.Builder attributes = AttributeSet.builder(18)
                .put("quarkus.level", sourceLevel.getName())
                .put("quarkus.sequence_number", record.getSequenceNumber());
        addSource(attributes, record);
        addProcess(attributes, record);
        addContext(attributes, record, Objects.requireNonNull(contextPolicySource.get(), "context policy snapshot"));
        if (template != null && !Objects.equals(template, rendered)) {
            attributes.put("quarkus.message_template", template);
        }
        if (record.getResourceBundleName() != null) {
            attributes.put("quarkus.resource_bundle", record.getResourceBundleName());
        }
        if (record.getMarker() != null) {
            attributes.put("quarkus.marker", String.valueOf(record.getMarker()));
        }

        Instant instant = record.getInstant();
        logger.log(
                level,
                null,
                rendered,
                null,
                attributes.build(),
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
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (attributes.isFull()) {
                attributes.markTruncated();
                break;
            }
            attributes.put("mdc." + entry.getKey(), entry.getValue());
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
            attributes.put("mdc." + key, value);
        }
    }

    private static String loggerName(ExtLogRecord record) {
        String name = record.getLoggerName();
        return name == null || name.isBlank() ? "quarkus" : name;
    }
}
