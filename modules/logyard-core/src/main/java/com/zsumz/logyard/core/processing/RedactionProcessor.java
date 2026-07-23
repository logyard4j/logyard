package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.processing.EventProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case-insensitive glob redaction over attribute keys. */
public final class RedactionProcessor implements EventProcessor {
    private final List<String> patterns;

    public RedactionProcessor(List<String> patterns) {
        List<String> normalized = new ArrayList<>();
        if (patterns != null) {
            for (String pattern : patterns) {
                normalized.add(pattern.toLowerCase(Locale.ROOT));
            }
        }
        this.patterns = List.copyOf(normalized);
    }

    @Override
    public LogEvent process(LogEvent event) {
        if (patterns.isEmpty() || event.attributes().isEmpty()) {
            return event;
        }
        AttributeSet.Builder result = AttributeSet.builder(event.attributes().size());
        boolean changed = false;
        for (int index = 0; index < event.attributes().size(); index++) {
            String key = event.attributes().keyAt(index);
            Object value = event.attributes().valueAt(index);
            if (matches(key.toLowerCase(Locale.ROOT))) {
                result.put(key, "[REDACTED]");
                changed = true;
            } else {
                result.putAll(AttributeSet.of(key, value));
            }
        }
        return changed ? event.withAttributes(result.build()) : event;
    }

    private boolean matches(String key) {
        for (String pattern : patterns) {
            if (glob(pattern, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean glob(String pattern, String value) {
        int p = 0;
        int v = 0;
        int star = -1;
        int checkpoint = -1;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) == value.charAt(v)) {
                p++;
                v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                checkpoint = v;
            } else if (star >= 0) {
                p = star + 1;
                v = ++checkpoint;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
