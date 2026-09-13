package com.logyard4j.logyard.api.event;

import java.util.Map;

/** Canonicalizes arbitrary map keys while preserving every distinct captured entry. */
final class CapturedMapKeys {
    private CapturedMapKeys() {
    }

    static String resolve(
            Object sourceKey,
            String renderedKey,
            Map<String, Object> captured,
            CaptureContext context) {
        String qualified = sourceKey instanceof String
                ? renderedKey
                : '[' + typeName(sourceKey) + "] " + renderedKey;
        NormalizedAttributeKey normalized;
        try {
            normalized = AttributeKey.normalize(qualified, true);
        } catch (IllegalArgumentException blankKey) {
            normalized = AttributeKey.normalize("[blank map key]", true);
            context.markTruncated();
        }
        if (normalized.truncated()) {
            context.markTruncated();
        }
        if (context.remainingPayloadCharacters() < normalized.storageKey().length()) {
            context.markTruncated();
            return null;
        }
        String storageKey = context.capturePayloadText(
                normalized.storageKey(),
                CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
        if (!captured.containsKey(storageKey)) {
            return storageKey;
        }
        context.markTruncated();
        for (int collision = 2; ; collision++) {
            String candidate = CaptureLimits.disambiguateAttributeKey(storageKey, collision);
            if (!captured.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    private static String typeName(Object key) {
        return key == null ? "null" : key.getClass().getName();
    }
}
