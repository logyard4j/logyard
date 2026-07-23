package com.zsumz.logyard.api.event;

/** Fixed alpha limits that make one captured event's memory use finite. */
public final class CaptureLimits {
    public static final int MAX_ARGUMENTS = 64;
    public static final int MAX_ATTRIBUTES = 128;
    public static final int MAX_COLLECTION_ELEMENTS = 128;
    public static final int MAX_NESTING_DEPTH = 8;
    public static final int MAX_TEXT_CHARS = 65_536;
    public static final int MAX_NAME_CHARS = 1_024;
    public static final int MAX_ATTRIBUTE_KEY_CHARS = 256;

    private CaptureLimits() {
    }

    /** Bounds arbitrary captured text without splitting a surrogate pair. */
    public static String text(String value) {
        return truncate(value, MAX_TEXT_CHARS);
    }

    /** Bounds a logger, output, processor, or other diagnostic name. */
    public static String name(String value) {
        return truncate(value, MAX_NAME_CHARS);
    }

    static String attributeKey(String value) {
        if (value.length() <= MAX_ATTRIBUTE_KEY_CHARS) {
            return value;
        }
        int suffixLength = 17;
        String hash = String.format(java.util.Locale.ROOT, "%016x", stableHash(value));
        return safePrefix(value, MAX_ATTRIBUTE_KEY_CHARS - suffixLength) + '~' + hash;
    }

    static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
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

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
