package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.processing.EventProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Case-insensitive glob redaction over attribute keys. */
public final class RedactionProcessor implements EventProcessor {
    private static final String REDACTED = "[REDACTED]";
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
        AttributeSet attributes = event.attributes();
        AttributeSet.Builder redacted = null;
        for (int index = 0; index < attributes.size(); index++) {
            String key = attributes.keyAt(index);
            if (matches(key)) {
                if (redacted == null) {
                    redacted = AttributeSet.builder(attributes.size()).putAll(attributes);
                }
                redacted.put(key, REDACTED);
            }
        }
        return redacted == null ? event : event.withAttributes(redacted.build());
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
            if (p < pattern.length() && equalsIgnoreCase(pattern.charAt(p), value.charAt(v))) {
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

    private static boolean equalsIgnoreCase(char left, char right) {
        return left == right
                || Character.toLowerCase(left) == Character.toLowerCase(right)
                || Character.toUpperCase(left) == Character.toUpperCase(right);
    }
}
