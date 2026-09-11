package com.logyard4j.core.processing;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.CapturedAttributeAccess;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.event.SystemAttributes;
import com.logyard4j.api.spi.processing.EventProcessor;

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
        boolean redactionTruncated = false;
        for (int index = 0; index < attributes.size(); index++) {
            String key = attributes.keyAt(index);
            Object current = attributes.valueAt(index);
            Object replacement;
            if (matchesAttribute(key)) {
                replacement = REDACTED;
            } else if (current instanceof java.util.Map<?, ?> || current instanceof java.util.List<?>) {
                if (nested == null) {
                    nested = new StructuredValueRedactor(this::matches, REDACTED);
                }
                StructuredValueRedactor.Result result = nested.redactChildren(current, key);
                replacement = result.value();
                redactionTruncated |= result.truncated();
            } else {
                replacement = current;
            }
            if (replacement != current) {
                if (redacted == null) {
                    redacted = AttributeSet.systemBuilder(attributes.size()).putAll(attributes);
                }
                CapturedAttributeAccess.replaceValue(redacted, key, replacement);
            }
        }
        if (redactionTruncated) {
            if (redacted == null) {
                redacted = AttributeSet.systemBuilder(attributes.size() + 1).putAll(attributes);
            }
            redacted.put(SystemAttributes.REDACTION_TRUNCATED, true);
        }
        return redacted == null ? event : event.withAttributes(redacted.build());
    }

    private boolean matches(CharSequence path, String leaf) {
        if (matchesCandidate(path, 0)) {
            return true;
        }
        if (leaf.isEmpty() || sameText(leaf, path)) {
            return false;
        }
        if (matchesCandidate(leaf, 0)) {
            return true;
        }
        int separator = terminalSeparator(leaf);
        return separator >= 0
                && separator + 1 < leaf.length()
                && matchesCandidate(leaf, separator + 1);
    }

    private boolean matchesAttribute(String key) {
        if (matchesCandidate(key, 0)) {
            return true;
        }
        int separator = terminalSeparator(key);
        return separator >= 0 && separator + 1 < key.length() && matchesCandidate(key, separator + 1);
    }

    private static int terminalSeparator(String key) {
        return Math.max(key.lastIndexOf('.'), key.lastIndexOf('~'));
    }

    private boolean matchesCandidate(CharSequence candidate, int start) {
        for (String pattern : patterns) {
            if (glob(pattern, candidate, start)) {
                return true;
            }
        }
        return false;
    }

    private static boolean glob(String pattern, CharSequence value, int start) {
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

    private static boolean sameText(String value, CharSequence candidate) {
        if (value.length() != candidate.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != candidate.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
