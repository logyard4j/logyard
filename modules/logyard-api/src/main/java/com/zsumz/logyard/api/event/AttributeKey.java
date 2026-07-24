package com.zsumz.logyard.api.event;

import java.util.Objects;

/** Applies bounded validation and storage normalization to an attribute key. */
final class AttributeKey {
    private AttributeKey() {
    }

    static String normalize(String key, boolean systemAttributesAllowed) {
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
