package com.logyard4j.logyard.opentelemetry;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.ExceptionSnapshot;
import com.logyard4j.logyard.api.event.LogEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;

/**
 * Copies one event's bounded attributes and identity onto a log record under semantic conventions.
 *
 * <p>Event attributes are written first and the Logyard-owned identity and exception attributes
 * afterwards, so an application attribute can never shadow {@code logger.name},
 * {@code thread.name}, {@code thread.id}, or captured exception keys, even by supplying a different value type.</p>
 */
final class OtelLogRecordAttributes {
    private static final AttributeKey<String> LOGGER_NAME = AttributeKey.stringKey("logger.name");
    private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("thread.name");
    private static final AttributeKey<Long> THREAD_ID = AttributeKey.longKey("thread.id");
    private static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");

    private OtelLogRecordAttributes() {
    }

    /**
     * Applies every attribute derived from one event.
     *
     * @param builder record builder being assembled
     * @param event event being bridged
     */
    static void apply(LogRecordBuilder builder, LogEvent event) {
        AttributeSet attributes = event.attributes();
        for (int index = 0; index < attributes.size(); index++) {
            String key = attributes.keyAt(index);
            if (!owned(key, event.exception() != null)) put(builder, key, attributes.valueAt(index));
        }
        if (event.loggerName() != null) {
            builder.setAttribute(LOGGER_NAME, event.loggerName());
        }
        if (event.threadName() != null) {
            builder.setAttribute(THREAD_NAME, event.threadName());
        }
        builder.setAttribute(THREAD_ID, event.threadId());
        applyException(builder, event.exception());
    }

    private static void applyException(LogRecordBuilder builder, ExceptionSnapshot exception) {
        if (exception == null) {
            return;
        }
        builder.setAttribute(EXCEPTION_TYPE, exception.type());
        if (exception.message() != null) {
            builder.setAttribute(EXCEPTION_MESSAGE, exception.message());
        }
        builder.setAttribute(EXCEPTION_STACKTRACE, OtelStackTrace.render(exception));
    }

    private static void put(LogRecordBuilder builder, String key, Object value) {
        if (value instanceof String text) {
            builder.setAttribute(key, text);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            builder.setAttribute(key, ((Number) value).longValue());
        } else if (value instanceof Float || value instanceof Double) {
            builder.setAttribute(key, ((Number) value).doubleValue());
        } else if (value instanceof Boolean flag) {
            builder.setAttribute(key, flag.booleanValue());
        } else if (value == null || value instanceof java.util.Map<?, ?> || value instanceof java.util.List<?>) {
            builder.setAttribute(key, OtelAttributeValue.capture(value));
        } else {
            builder.setAttribute(key, String.valueOf(value));
        }
    }
    private static boolean owned(String key, boolean hasException) {
        return key.equals("logger.name") || key.equals("thread.name") || key.equals("thread.id")
                || hasException && (key.equals("exception.type") || key.equals("exception.message")
                || key.equals("exception.stacktrace"));
    }
}
