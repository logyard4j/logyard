package com.zsumz.logyard.api.format;

import com.zsumz.logyard.api.event.CaptureLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A deliberately small, validated text-template grammar for one-line event formatting.
 *
 * <p>Supported placeholders are {@code timestamp}, {@code level}, {@code logger},
 * {@code thread}, {@code event}, {@code message}, and {@code fields}. Literal braces
 * are written as <code>{{</code> and <code>}}</code>. Templates have no conditionals,
 * expressions, reflection, or arbitrary field lookup.</p>
 */
public final class TextTemplate {
    public static final int MAX_TEMPLATE_CHARS = 4_096;
    public static final int MAX_PLACEHOLDERS = 64;
    public static final Set<String> PLACEHOLDERS = Set.of(
            "timestamp", "level", "logger", "thread", "event", "message", "fields");

    private final String source;
    private final List<Segment> segments;

    private TextTemplate(String source, List<Segment> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
    }

    /** Compiles and validates a one-line template. */
    public static TextTemplate compile(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("text template must not be blank");
        }
        if (source.length() > MAX_TEMPLATE_CHARS) {
            throw new IllegalArgumentException(
                    "text template exceeds " + MAX_TEMPLATE_CHARS + " characters");
        }
        rejectControls(source);

        List<Segment> segments = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int placeholders = 0;
        boolean message = false;
        int cursor = 0;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            if (current == '{') {
                if (cursor + 1 < source.length() && source.charAt(cursor + 1) == '{') {
                    literal.append('{');
                    cursor += 2;
                    continue;
                }
                flushLiteral(segments, literal);
                int closing = source.indexOf('}', cursor + 1);
                if (closing < 0) {
                    throw syntax(cursor, "unclosed placeholder");
                }
                String name = source.substring(cursor + 1, closing);
                if (!PLACEHOLDERS.contains(name)) {
                    throw syntax(cursor, "unknown placeholder '" + name + "'");
                }
                placeholders++;
                if (placeholders > MAX_PLACEHOLDERS) {
                    throw new IllegalArgumentException(
                            "text template contains more than " + MAX_PLACEHOLDERS + " placeholders");
                }
                message |= "message".equals(name);
                segments.add(new Segment(null, name));
                cursor = closing + 1;
                continue;
            }
            if (current == '}') {
                if (cursor + 1 < source.length() && source.charAt(cursor + 1) == '}') {
                    literal.append('}');
                    cursor += 2;
                    continue;
                }
                throw syntax(cursor, "unmatched closing brace");
            }
            literal.append(current);
            cursor++;
        }
        flushLiteral(segments, literal);
        if (!message) {
            throw new IllegalArgumentException("text template must contain {message}");
        }
        return new TextTemplate(source, segments);
    }

    public String source() {
        return source;
    }

    /** Renders using a bounded value resolver. Missing values become empty strings. */
    public String render(Function<String, String> values) {
        Objects.requireNonNull(values, "values");
        StringBuilder result = new StringBuilder(Math.min(source.length() + 128, 1_024));
        for (Segment segment : segments) {
            String value = segment.literal() == null
                    ? Objects.requireNonNullElse(values.apply(segment.placeholder()), "")
                    : segment.literal();
            int remaining = CaptureLimits.MAX_TEXT_CHARS - result.length();
            if (remaining <= 0) {
                break;
            }
            if (value.length() <= remaining) {
                result.append(value);
            } else {
                result.append(value, 0, safeEnd(value, remaining));
                break;
            }
        }
        return result.toString();
    }

    private static void flushLiteral(List<Segment> segments, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        segments.add(new Segment(literal.toString(), null));
        literal.setLength(0);
    }

    private static int safeEnd(String value, int requested) {
        int end = Math.min(value.length(), requested);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return end;
    }

    private static void rejectControls(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw syntax(index, "control characters are not allowed");
            }
        }
    }

    private static IllegalArgumentException syntax(int offset, String message) {
        return new IllegalArgumentException(
                "invalid text template at character " + (offset + 1) + ": " + message);
    }

    private record Segment(String literal, String placeholder) {
    }
}
