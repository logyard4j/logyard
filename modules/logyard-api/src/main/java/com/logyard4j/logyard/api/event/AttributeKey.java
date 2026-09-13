package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.annotation.InternalApi;

import java.util.Objects;

/**
 * Applies bounded validation and storage normalization to an attribute key.
 *
 * @hidden
 */
@InternalApi
public final class AttributeKey {
    private AttributeKey() {
    }

    /**
     * Validates a caller key and creates its bounded storage identity.
     *
     * @param key source attribute key
     * @param systemAttributesAllowed whether the reserved {@code logyard.*} namespace is allowed
     * @return validated key normalization result
     */
    public static NormalizedAttributeKey normalize(String key, boolean systemAttributesAllowed) {
        String storageKey = normalizeStorage(key, systemAttributesAllowed);
        return new NormalizedAttributeKey(key, storageKey, storageKey != key);
    }

    static String normalizeStorage(String key, boolean systemAttributesAllowed) {
        Objects.requireNonNull(key, "attribute key");
        if (isBlankBounded(key)) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        if (!systemAttributesAllowed && AttributeSet.isReservedKey(key)) {
            throw new IllegalArgumentException("attribute keys in the logyard.* namespace are reserved");
        }
        return CaptureLimits.attributeKey(key);
    }

    private static boolean isBlankBounded(String key) {
        if (key.isEmpty()) {
            return true;
        }
        int inspected = Math.min(key.length(), CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        for (int index = 0; index < inspected; index++) {
            if (!Character.isWhitespace(key.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
