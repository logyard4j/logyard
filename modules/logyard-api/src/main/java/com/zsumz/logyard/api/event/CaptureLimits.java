package com.zsumz.logyard.api.event;

/** Fixed alpha limits that make one captured event's memory use finite. */
public final class CaptureLimits {
    /** Maximum positional arguments retained by one event. */
    public static final int MAX_ARGUMENTS = 64;

    /** Maximum attributes retained by one event, including truncation markers. */
    public static final int MAX_ATTRIBUTES = 128;

    /** Maximum elements retained from a captured collection, map, or array. */
    public static final int MAX_COLLECTION_ELEMENTS = 128;

    /** Maximum recursive depth retained from nested values. */
    public static final int MAX_NESTING_DEPTH = 8;

    /** Maximum UTF-16 characters retained from ordinary captured text. */
    public static final int MAX_TEXT_CHARS = 65_536;

    /** Maximum UTF-16 characters retained from names used in diagnostics and routing. */
    public static final int MAX_NAME_CHARS = 1_024;

    /** Maximum UTF-16 characters retained from an attribute key. */
    public static final int MAX_ATTRIBUTE_KEY_CHARS = 256;

    /** Maximum distinct non-scalar values captured across one event. */
    public static final int MAX_EVENT_NODES = 2_048;

    /** Maximum container entries, arguments, attributes, and frames captured across one event. */
    public static final int MAX_EVENT_ENTRIES = 4_096;

    /** Maximum UTF-16 characters retained across all captured and rendered text in one event. */
    public static final int MAX_EVENT_TEXT_CHARS = 262_144;

    /** Maximum throwable nodes captured across one event. */
    public static final int MAX_EVENT_EXCEPTION_NODES = 64;

    /** Maximum stack frames captured across one event. */
    public static final int MAX_EVENT_STACK_FRAMES = 1_024;

    private CaptureLimits() {
    }

    /**
     * Bounds arbitrary captured text without splitting a surrogate pair.
     *
     * @param value text to bound, or {@code null}
     * @return bounded text, preserving {@code null}
     */
    public static String text(String value) {
        return truncate(value, MAX_TEXT_CHARS);
    }

    /**
     * Bounds a logger, output, processor, or other diagnostic name.
     *
     * @param value name to bound, or {@code null}
     * @return bounded name, preserving {@code null}
     */
    public static String name(String value) {
        return truncate(value, MAX_NAME_CHARS);
    }

    /**
     * Produces the bounded storage form of an attribute key while preserving its leaf segment.
     *
     * @param value non-null attribute key
     * @return bounded key suitable for storage and leaf-based security matching
     */
    public static String attributeKey(String value) {
        if (value.length() <= MAX_ATTRIBUTE_KEY_CHARS) {
            return value;
        }
        String hash = String.format(java.util.Locale.ROOT, "%016x", stableHash(value));
        int separator = value.lastIndexOf('.');
        if (separator < 0 || separator == value.length() - 1) {
            return safePrefix(value, MAX_ATTRIBUTE_KEY_CHARS - 17) + '~' + hash;
        }

        String leaf = value.substring(separator + 1);
        int maximumLeafLength = Math.min(leaf.length(), MAX_ATTRIBUTE_KEY_CHARS / 2);
        String boundedLeaf = safeSuffix(leaf, maximumLeafLength);
        int prefixLength = MAX_ATTRIBUTE_KEY_CHARS - 18 - boundedLeaf.length();
        return safePrefix(value, prefixLength) + '~' + hash + '.' + boundedLeaf;
    }

    static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        if (maximum <= 0) {
            return "";
        }
        return safePrefix(value, maximum - 1) + '…';
    }

    private static String safePrefix(String value, int requestedLength) {
        int length = Math.max(0, Math.min(value.length(), requestedLength));
        if (length > 0 && length < value.length()
                && Character.isHighSurrogate(value.charAt(length - 1))
                && Character.isLowSurrogate(value.charAt(length))) {
            length--;
        }
        return value.substring(0, length);
    }

    private static String safeSuffix(String value, int requestedLength) {
        int start = Math.max(0, value.length() - Math.max(0, requestedLength));
        if (start > 0 && start < value.length()
                && Character.isHighSurrogate(value.charAt(start - 1))
                && Character.isLowSurrogate(value.charAt(start))) {
            start++;
        }
        return value.substring(start);
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
