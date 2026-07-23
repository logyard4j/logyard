package com.zsumz.logyard.output.json.encoding;

import com.zsumz.logyard.api.event.ExceptionSnapshot;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.EventEncoder;

import java.lang.reflect.Array;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Direct, non-thread-safe JSON encoder for Logyard's event model. No event tree is created. */
public final class JsonEncoder implements EventEncoder {
    private static final int MAX_NESTING = 12;

    private final ResourceAttributes resource;
    private final JsonProfile profile;
    private final StringBuilder buffer = new StringBuilder(1024);

    public JsonEncoder(ResourceAttributes resource) {
        this(resource, JsonProfile.named("logyard"));
    }

    public JsonEncoder(ResourceAttributes resource, JsonProfile profile) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    @Override
    public String encode(LogEvent event) {
        Objects.requireNonNull(event, "event");
        buffer.setLength(0);
        buffer.append('{');
        boolean first = true;
        first = stringField(first, "timestamp", Instant.ofEpochMilli(event.timestampMillis()).toString());
        first = longField(first, "observed_timestamp_unix_nano", event.observedTimestampUnixNanos());
        first = longField(first, "severity_number", event.level().severityNumber());
        first = stringField(first, "severity_text", event.level().name());
        first = stringField(first, "logger", event.loggerName());
        if (event.eventName() != null && !event.eventName().isBlank()) {
            first = stringField(first, "event_name", event.eventName());
        }
        first = stringField(first, "body", event.renderedMessage());
        if (event.messageTemplate() != null) {
            first = stringField(first, "message_template", event.messageTemplate());
        }
        first = attributes(first, event);
        if (profile.emits("resource")) {
            first = beginField(first, profile.outputName("resource"));
            value(resource.values(), new IdentityHashMap<>(), 0);
        }
        if (profile.emits("thread")) {
            first = beginField(first, profile.outputName("thread"));
            buffer.append('{');
            field("id", event.threadId());
            comma();
            field("name", event.threadName());
            buffer.append('}');
        }
        if (event.exception() != null && profile.emits("exception")) {
            first = beginField(first, profile.outputName("exception"));
            exception(event.exception());
        }
        buffer.append('}');
        return buffer.toString();
    }

    @Override
    public String mediaType() {
        return "application/json; charset=utf-8";
    }

    public JsonProfile profile() {
        return profile;
    }

    private boolean attributes(boolean first, LogEvent event) {
        JsonAttributeTransform transform = profile.attributes();
        if (transform.mode() == JsonAttributeTransform.Mode.DROP || !profile.emits("attributes")) {
            return first;
        }
        if (transform.mode() == JsonAttributeTransform.Mode.NESTED) {
            first = beginField(first, profile.outputName("attributes"));
            buffer.append('{');
            boolean firstAttribute = true;
            Set<String> names = new HashSet<>();
            for (int index = 0; index < event.attributes().size(); index++) {
                String source = event.attributes().keyAt(index);
                if (!transform.includes(source)) {
                    continue;
                }
                String output = transform.outputName(source);
                requireUniqueAttribute(names, output);
                if (!firstAttribute) {
                    comma();
                }
                firstAttribute = false;
                name(output);
                value(event.attributes().valueAt(index), new IdentityHashMap<>(), 0);
            }
            buffer.append('}');
            return first;
        }
        Set<String> names = new HashSet<>();
        for (int index = 0; index < event.attributes().size(); index++) {
            String source = event.attributes().keyAt(index);
            if (!transform.includes(source)) {
                continue;
            }
            String output = transform.prefix() + transform.outputName(source);
            requireUniqueAttribute(names, output);
            first = beginField(first, output);
            value(event.attributes().valueAt(index), new IdentityHashMap<>(), 0);
        }
        return first;
    }

    private static void requireUniqueAttribute(Set<String> names, String output) {
        if (!names.add(output)) {
            throw new IllegalArgumentException(
                    "JSON attribute transforms produce duplicate field '" + output + "'");
        }
    }

    private boolean stringField(boolean first, String canonical, String value) {
        if (!profile.emits(canonical)) {
            return first;
        }
        first = beginField(first, profile.outputName(canonical));
        string(value);
        return first;
    }

    private boolean longField(boolean first, String canonical, long value) {
        if (!profile.emits(canonical)) {
            return first;
        }
        first = beginField(first, profile.outputName(canonical));
        buffer.append(value);
        return first;
    }

    private boolean beginField(boolean first, String outputName) {
        if (!first) {
            comma();
        }
        name(outputName);
        return false;
    }

    private void exception(ExceptionSnapshot exception) {
        buffer.append('{');
        field("type", exception.type());
        if (exception.message() != null) {
            comma();
            field("message", exception.message());
        }
        comma();
        name("stacktrace");
        buffer.append('[');
        for (int index = 0; index < exception.frames().size(); index++) {
            if (index > 0) {
                comma();
            }
            string(exception.frames().get(index).toString());
        }
        buffer.append(']');
        if (exception.truncated()) {
            comma();
            field("truncated", true);
        }
        if (!exception.suppressed().isEmpty()) {
            comma();
            name("suppressed");
            buffer.append('[');
            for (int index = 0; index < exception.suppressed().size(); index++) {
                if (index > 0) {
                    comma();
                }
                exception(exception.suppressed().get(index));
            }
            buffer.append(']');
        }
        if (exception.cause() != null) {
            comma();
            name("cause");
            exception(exception.cause());
        }
        buffer.append('}');
    }

    private void value(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null) {
            buffer.append("null");
        } else if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            string(String.valueOf(value));
        } else if (value instanceof Boolean booleanValue) {
            buffer.append(booleanValue);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            buffer.append(value);
        } else if (value instanceof Float floatValue) {
            finiteNumber(floatValue.doubleValue(), value.toString());
        } else if (value instanceof Double doubleValue) {
            finiteNumber(doubleValue, value.toString());
        } else if (value instanceof java.math.BigDecimal decimal) {
            buffer.append(decimal.toPlainString());
        } else if (depth >= MAX_NESTING || visiting.put(value, Boolean.TRUE) != null) {
            string("[truncated]");
        } else {
            try {
                if (value instanceof Map<?, ?> map) {
                    buffer.append('{');
                    int index = 0;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (index++ > 0) {
                            comma();
                        }
                        name(String.valueOf(entry.getKey()));
                        value(entry.getValue(), visiting, depth + 1);
                    }
                    buffer.append('}');
                } else if (value instanceof Collection<?> collection) {
                    buffer.append('[');
                    int index = 0;
                    for (Object item : collection) {
                        if (index++ > 0) {
                            comma();
                        }
                        value(item, visiting, depth + 1);
                    }
                    buffer.append(']');
                } else if (value.getClass().isArray()) {
                    buffer.append('[');
                    int length = Array.getLength(value);
                    for (int index = 0; index < length; index++) {
                        if (index > 0) {
                            comma();
                        }
                        value(Array.get(value, index), visiting, depth + 1);
                    }
                    buffer.append(']');
                } else {
                    string(String.valueOf(value));
                }
            } finally {
                visiting.remove(value);
            }
        }
    }

    private void field(String name, String value) {
        name(name);
        string(value);
    }

    private void field(String name, long value) {
        name(name);
        buffer.append(value);
    }

    private void field(String name, boolean value) {
        name(name);
        buffer.append(value);
    }

    private void name(String value) {
        string(value);
        buffer.append(':');
    }

    private void string(String value) {
        buffer.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> buffer.append("\\\"");
                case '\\' -> buffer.append("\\\\");
                case '\b' -> buffer.append("\\b");
                case '\f' -> buffer.append("\\f");
                case '\n' -> buffer.append("\\n");
                case '\r' -> buffer.append("\\r");
                case '\t' -> buffer.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        appendUnicode(character);
                    } else {
                        buffer.append(character);
                    }
                }
            }
        }
        buffer.append('"');
    }

    private void appendUnicode(char character) {
        String hex = Integer.toHexString(character);
        buffer.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
    }

    private void finiteNumber(double value, String representation) {
        if (Double.isFinite(value)) {
            buffer.append(representation);
        } else {
            string(representation);
        }
    }

    private void comma() {
        buffer.append(',');
    }
}
