package com.zsumz.logyard.api.event;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fixed limits that make one captured event's memory use finite.
 *
 * <p>The published numeric values are stable API constants. A future release may add a new
 * limit, but it will not silently change an existing inlined value.</p>
 */
public final class CaptureLimits {
    private static final int ATTRIBUTE_HASH_EDGE_CHARS = 1_024;
    private static final int ATTRIBUTE_HASH_SAMPLES = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

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
    public static final int MAX_EVENT_TEXT_CHARS = 65_536;

    /** Reserved event-wide allowance for logger, event, and thread identity. */
    public static final int MAX_EVENT_IDENTITY_CHARS = 4_096;

    /** Reserved event-wide allowance for the message template. */
    public static final int MAX_EVENT_TEMPLATE_CHARS = 8_192;

    /** Reserved event-wide allowance for the lazily rendered primary message. */
    public static final int MAX_RENDERED_MESSAGE_CHARS = 16_384;

    /** Maximum estimated characters a JDK-style adapter formatter may construct before bounded capture. */
    public static final int MAX_FORMATTER_WORK_CHARS = 65_536;

    /** Reserved event-wide allowance for exception types, messages, and stack-frame fields. */
    public static final int MAX_EVENT_EXCEPTION_TEXT_CHARS = 16_384;

    /** Reserved portion of the exception allowance for throwable type names. */
    public static final int MAX_EVENT_EXCEPTION_TYPE_CHARS = 2_048;

    /** Reserved portion of the exception allowance for throwable messages. */
    public static final int MAX_EVENT_EXCEPTION_MESSAGE_CHARS = 8_192;

    /** Reserved portion of the exception allowance for stack-frame fields. */
    public static final int MAX_EVENT_EXCEPTION_FRAME_CHARS = 6_144;

    /** Event-wide allowance for argument, attribute, and processor-enrichment text. */
    public static final int MAX_EVENT_PAYLOAD_TEXT_CHARS = 20_480;

    /** Maximum canonical characters retained for one arbitrary-precision number. */
    public static final int MAX_CAPTURED_NUMBER_CHARS = 2_048;

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
        String hash = boundedHash(value);
        int separator = lastSeparatorInBoundedSuffix(value);
        if (separator < 0 || separator == value.length() - 1) {
            int suffixLength = Math.min(value.length(), MAX_ATTRIBUTE_KEY_CHARS / 2);
            String suffix = safeSuffix(value, suffixLength);
            int prefixLength = MAX_ATTRIBUTE_KEY_CHARS - 18 - suffix.length();
            return safePrefix(value, prefixLength) + '~' + hash + '~' + suffix;
        }

        String leaf = value.substring(separator + 1);
        int maximumLeafLength = Math.min(leaf.length(), MAX_ATTRIBUTE_KEY_CHARS / 2);
        String boundedLeaf = safeSuffix(leaf, maximumLeafLength);
        int prefixLength = MAX_ATTRIBUTE_KEY_CHARS - 18 - boundedLeaf.length();
        return safePrefix(value, prefixLength) + '~' + hash + '.' + boundedLeaf;
    }

    static String disambiguateAttributeKey(String key, int collisionIndex) {
        String marker = "~collision-" + collisionIndex;
        int suffixSeparator = Math.max(key.lastIndexOf('.'), key.lastIndexOf('~'));
        String suffix = suffixSeparator < 0 ? "" : key.substring(suffixSeparator);
        int prefixLength = MAX_ATTRIBUTE_KEY_CHARS - marker.length() - suffix.length();
        return safePrefix(key, prefixLength) + marker + suffix;
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

    private static int lastSeparatorInBoundedSuffix(String value) {
        int minimum = Math.max(0, value.length() - MAX_ATTRIBUTE_KEY_CHARS / 2);
        for (int index = value.length() - 1; index >= minimum; index--) {
            if (value.charAt(index) == '.') {
                return index;
            }
        }
        return -1;
    }

    private static String boundedHash(String value) {
        MessageDigest digest = sha256();
        updateInt(digest, value.length());
        int prefixEnd = Math.min(value.length(), ATTRIBUTE_HASH_EDGE_CHARS);
        updateRange(digest, value, 0, prefixEnd);
        int suffixStart = Math.max(prefixEnd, value.length() - ATTRIBUTE_HASH_EDGE_CHARS);
        updateRange(digest, value, suffixStart, value.length());
        if (value.length() > 1) {
            for (int sample = 0; sample < ATTRIBUTE_HASH_SAMPLES; sample++) {
                int index = (int) ((long) sample * (value.length() - 1) / (ATTRIBUTE_HASH_SAMPLES - 1));
                updateChar(digest, value.charAt(index));
            }
        }
        byte[] hash = digest.digest();
        char[] encoded = new char[16];
        for (int index = 0; index < 8; index++) {
            encoded[index * 2] = HEX[(hash[index] >>> 4) & 0xf];
            encoded[index * 2 + 1] = HEX[hash[index] & 0xf];
        }
        return new String(encoded);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateRange(MessageDigest digest, String value, int start, int end) {
        for (int index = start; index < end; index++) {
            updateChar(digest, value.charAt(index));
        }
    }

    private static void updateChar(MessageDigest digest, char value) {
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
