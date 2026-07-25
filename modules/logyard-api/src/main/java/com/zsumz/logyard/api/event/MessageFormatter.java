package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.failure.FailureIsolation;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Small SLF4J-style brace formatter with guarded, bounded object rendering. */
public final class MessageFormatter {
    private MessageFormatter() {
    }

    /**
     * Renders SLF4J-style {@code {}} placeholders with captured arguments.
     *
     * @param template message template, or {@code null}
     * @param arguments positional arguments
     * @return bounded rendered message
     */
    public static String format(String template, Object[] arguments) {
        return formatResult(template, arguments, CaptureLimits.MAX_TEXT_CHARS).value();
    }

    static RenderResult formatResult(String template, Object[] arguments, int maximumCharacters) {
        BoundedText result = new BoundedText(maximumCharacters);
        if (template == null) {
            return result.append("null").result();
        }
        if (arguments == null || arguments.length == 0) {
            return result.append(template).result();
        }

        int cursor = 0;
        int argument = 0;
        RenderState state = new RenderState();
        while (argument < arguments.length && !result.full()) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0) {
                break;
            }
            if (placeholder > 0 && template.charAt(placeholder - 1) == '\\') {
                if (placeholder > 1 && template.charAt(placeholder - 2) == '\\') {
                    result.append(template, cursor, placeholder - 1);
                    appendValue(result, arguments[argument++], state, 0);
                } else {
                    result.append(template, cursor, placeholder - 1).append("{}");
                }
            } else {
                result.append(template, cursor, placeholder);
                appendValue(result, arguments[argument++], state, 0);
            }
            cursor = placeholder + 2;
        }
        result.append(template, cursor, template.length());
        return result.result();
    }

    /**
     * Renders an arbitrary value without allowing an ordinary {@code toString()} failure to escape.
     *
     * @param value value to render
     * @return bounded rendered value
     */
    public static String safeToString(Object value) {
        return safeRender(value, CaptureLimits.MAX_TEXT_CHARS).value();
    }

    static RenderResult safeRender(Object value, int maximumCharacters) {
        BoundedText result = new BoundedText(maximumCharacters);
        appendValue(result, value, new RenderState(), 0);
        return result.result();
    }

    private static void appendValue(BoundedText result, Object value, RenderState state, int depth) {
        if (value == null) {
            result.append("null");
            return;
        }
        boolean structured = value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Iterable<?>;
        if (!structured) {
            appendObject(result, value);
            return;
        }
        if (depth >= CaptureLimits.MAX_NESTING_DEPTH) {
            result.append("[maximum nesting depth reached]");
            return;
        }
        if (!state.firstVisit(value)) {
            result.append("[shared reference]");
            return;
        }
        if (value.getClass().isArray()) {
            appendArray(result, value, state, depth);
        } else if (value instanceof Map<?, ?> map) {
            appendMap(result, map, state, depth);
        } else {
            appendIterable(result, (Iterable<?>) value, state, depth);
        }
    }

    private static void appendArray(BoundedText result, Object array, RenderState state, int depth) {
        result.append('[');
        int sourceLength = Array.getLength(array);
        int length = Math.min(sourceLength, CaptureLimits.MAX_COLLECTION_ELEMENTS);
        for (int index = 0; index < length && !result.full(); index++) {
            if (!state.claimEntry()) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append("[render traversal budget exhausted]");
                break;
            }
            if (index > 0) {
                result.append(", ");
            }
            appendValue(result, Array.get(array, index), state, depth + 1);
        }
        if (sourceLength > length) {
            result.append(", ... ").append(sourceLength - length).append(" element(s) omitted");
        }
        result.append(']');
    }

    private static void appendMap(BoundedText result, Map<?, ?> map, RenderState state, int depth) {
        result.append('{');
        Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
        int index = 0;
        while (index < CaptureLimits.MAX_COLLECTION_ELEMENTS && entries.hasNext() && !result.full()) {
            if (!state.claimEntry()) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append("[render traversal budget exhausted]");
                break;
            }
            Map.Entry<?, ?> entry = entries.next();
            if (index++ > 0) {
                result.append(", ");
            }
            appendValue(result, entry.getKey(), state, depth + 1);
            result.append('=');
            appendValue(result, entry.getValue(), state, depth + 1);
        }
        if (entries.hasNext()) {
            result.append(", ...");
        }
        result.append('}');
    }

    private static void appendIterable(BoundedText result, Iterable<?> iterable, RenderState state, int depth) {
        result.append('[');
        Iterator<?> values = iterable.iterator();
        int index = 0;
        while (index < CaptureLimits.MAX_COLLECTION_ELEMENTS && values.hasNext() && !result.full()) {
            if (!state.claimEntry()) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append("[render traversal budget exhausted]");
                break;
            }
            Object value = values.next();
            if (index++ > 0) {
                result.append(", ");
            }
            appendValue(result, value, state, depth + 1);
        }
        if (values.hasNext()) {
            result.append(", ...");
        }
        result.append(']');
    }

    private static void appendObject(BoundedText result, Object value) {
        try {
            if (value instanceof Enum<?> enumeration) {
                result.append(enumeration.name());
            } else if (value.getClass() == BigInteger.class) {
                result.append(String.valueOf(SafeNumberCapture.bigInteger((BigInteger) value)));
            } else if (value.getClass() == BigDecimal.class) {
                result.append(String.valueOf(SafeNumberCapture.bigDecimal((BigDecimal) value)));
            } else if (value.getClass() == java.util.Date.class) {
                result.append(CapturedTemporal.from((java.util.Date) value).toString());
            } else if (value instanceof CharSequence sequence) {
                result.append(sequence);
            } else {
                result.append(String.valueOf(value));
            }
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            result.append("[FAILED toString(): ").append(failure.getClass().getSimpleName()).append(']');
        }
    }

    private static final class RenderState {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private int remainingEntries = CaptureLimits.MAX_EVENT_ENTRIES;

        private boolean firstVisit(Object value) {
            return seen.put(value, Boolean.TRUE) == null;
        }

        private boolean claimEntry() {
            if (remainingEntries == 0) {
                return false;
            }
            remainingEntries--;
            return true;
        }
    }

    private static final class BoundedText {
        private final int maximum;
        private final StringBuilder value;
        private boolean truncated;

        private BoundedText(int maximum) {
            this.maximum = Math.max(0, maximum);
            value = new StringBuilder(Math.min(this.maximum, 128));
        }

        private BoundedText append(CharSequence source) {
            return append(source, 0, source.length());
        }

        private BoundedText append(CharSequence source, int start, int end) {
            int available = maximum - value.length();
            int length = end - start;
            if (length <= available) {
                value.append(source, start, end);
            } else {
                int copied = Math.max(0, available);
                if (copied > 0 && start + copied < end
                        && Character.isHighSurrogate(source.charAt(start + copied - 1))
                        && Character.isLowSurrogate(source.charAt(start + copied))) {
                    copied--;
                }
                value.append(source, start, start + copied);
                truncated = true;
            }
            return this;
        }

        private BoundedText append(char character) {
            if (value.length() < maximum) {
                value.append(character);
            } else {
                truncated = true;
            }
            return this;
        }

        private BoundedText append(int number) {
            return append(Integer.toString(number));
        }

        private boolean full() {
            return value.length() >= maximum;
        }

        private RenderResult result() {
            if (truncated && maximum > 0) {
                if (value.length() == maximum) {
                    int last = value.length() - 1;
                    if (last > 0 && Character.isLowSurrogate(value.charAt(last)) && Character.isHighSurrogate(value.charAt(last - 1))) {
                        value.deleteCharAt(last);
                        last--;
                    }
                    value.setCharAt(last, '…');
                } else {
                    value.append('…');
                }
            }
            return new RenderResult(value.toString(), truncated);
        }
    }

    record RenderResult(String value, boolean truncated) {
    }
}
