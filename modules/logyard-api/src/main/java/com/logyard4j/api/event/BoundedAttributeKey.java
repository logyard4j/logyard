package com.logyard4j.api.event;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates bounded attribute storage keys while preserving useful leaf segments. */
final class BoundedAttributeKey {
    private static final int HASH_EDGE_CHARS = 1_024;
    private static final int HASH_SAMPLES = 32;
    private static final int HASH_OVERHEAD_CHARS = 18;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BoundedAttributeKey() {
    }

    static String normalize(String value) {
        if (value.length() <= CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS) {
            return value;
        }
        String hash = boundedHash(value);
        int separator = lastSeparatorInBoundedSuffix(value);
        if (separator < 0 || separator == value.length() - 1) {
            int suffixLength = Math.min(value.length(), CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS / 2);
            String suffix = SurrogateSafeText.suffix(value, suffixLength);
            int prefixLength = CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS - HASH_OVERHEAD_CHARS - suffix.length();
            return SurrogateSafeText.prefix(value, prefixLength) + '~' + hash + '~' + suffix;
        }

        String leaf = value.substring(separator + 1);
        int maximumLeafLength = Math.min(leaf.length(), CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS / 2);
        String boundedLeaf = SurrogateSafeText.suffix(leaf, maximumLeafLength);
        int prefixLength = CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS - HASH_OVERHEAD_CHARS - boundedLeaf.length();
        return SurrogateSafeText.prefix(value, prefixLength) + '~' + hash + '.' + boundedLeaf;
    }

    static String disambiguate(String key, int collisionIndex) {
        String marker = "~collision-" + collisionIndex;
        int suffixSeparator = Math.max(key.lastIndexOf('.'), key.lastIndexOf('~'));
        String suffix = suffixSeparator < 0 ? "" : key.substring(suffixSeparator);
        int prefixLength = CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS - marker.length() - suffix.length();
        return SurrogateSafeText.prefix(key, prefixLength) + marker + suffix;
    }

    private static int lastSeparatorInBoundedSuffix(String value) {
        int minimum = Math.max(0, value.length() - CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS / 2);
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
        int prefixEnd = Math.min(value.length(), HASH_EDGE_CHARS);
        updateRange(digest, value, 0, prefixEnd);
        int suffixStart = Math.max(prefixEnd, value.length() - HASH_EDGE_CHARS);
        updateRange(digest, value, suffixStart, value.length());
        if (value.length() > 1) {
            for (int sample = 0; sample < HASH_SAMPLES; sample++) {
                int index = (int) ((long) sample * (value.length() - 1) / (HASH_SAMPLES - 1));
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
