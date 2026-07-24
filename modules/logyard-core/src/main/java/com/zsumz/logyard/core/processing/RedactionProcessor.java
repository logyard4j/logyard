package com.zsumz.logyard.core.processing;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.spi.processing.EventProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Case-insensitive glob redaction over complete attribute paths and leaf keys.
 *
 * <p>Nested maps use dot-separated paths. List positions use zero-based bracket segments such as
 * {@code request.users[0].token}; a {@code *} glob may match an index segment. Leaf matching
 * applies to map and attribute keys, never to a numeric list index.</p>
 */
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
        StructuredValueRedactor nested = null;
        AttributeSet.Builder redacted = null;
        for (int index = 0; index < attributes.size(); index++) {
            String key = attributes.keyAt(index);
            Object current = attributes.valueAt(index);
            Object replacement;
            if (matchesAttribute(key)) {
                replacement = REDACTED;
            } else if (current instanceof java.util.Map<?, ?> || current instanceof java.util.List<?>) {
                if (nested == null) {
                    nested = new StructuredValueRedactor(this::matches, REDACTED, event.remainingTraversalEntries());
                }
                replacement = nested.redactChildren(current, key);
            } else {
                replacement = current;
            }
            if (replacement != current) {
                if (redacted == null) {
                    redacted = AttributeSet.systemBuilder(attributes.size()).putAll(attributes);
                }
                redacted.put(key, replacement);
            }
        }
        return redacted == null ? event : event.withAttributes(redacted.build());
    }

    private boolean matches(String path, String leaf) {
        if (matchesCandidate(path, 0)) {
            return true;
        }
        if (leaf.isEmpty() || leaf.equals(path)) {
            return false;
        }
        if (matchesCandidate(leaf, 0)) {
            return true;
        }
        int separator = leaf.lastIndexOf('.');
        return separator >= 0
                && separator + 1 < leaf.length()
                && matchesCandidate(leaf, separator + 1);
    }

    private boolean matchesAttribute(String key) {
        if (matchesCandidate(key, 0)) {
            return true;
        }
        int separator = key.lastIndexOf('.');
        return separator >= 0 && separator + 1 < key.length() && matchesCandidate(key, separator + 1);
    }

    private boolean matchesCandidate(String candidate, int start) {
        for (String pattern : patterns) {
            if (glob(pattern, candidate, start)) {
                return true;
            }
        }
        return false;
    }

    private static boolean glob(String pattern, String value, int start) {
        int p = 0;
        int v = start;
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
