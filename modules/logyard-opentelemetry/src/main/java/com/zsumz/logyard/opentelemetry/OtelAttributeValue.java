package com.zsumz.logyard.opentelemetry;

import io.opentelemetry.api.common.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts detached, already bounded attribute trees without flattening their structure. */
final class OtelAttributeValue {
    private OtelAttributeValue() {
    }

    static Value<?> capture(Object value) {
        if (value == null) return Value.empty();
        if (value instanceof Boolean flag) return Value.of(flag.booleanValue());
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Value.of(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) return Value.of(((Number) value).doubleValue());
        if (value instanceof List<?> list) {
            List<Value<?>> values = new ArrayList<>(list.size());
            for (Object item : list) values.add(capture(item));
            return Value.of(values);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Value<?>> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put((String) entry.getKey(), capture(entry.getValue()));
            }
            return Value.of(values);
        }
        return Value.of(String.valueOf(value));
    }
}
