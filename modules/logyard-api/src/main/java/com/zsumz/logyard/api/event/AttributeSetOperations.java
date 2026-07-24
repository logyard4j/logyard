package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.Objects;

/** Trusted immutable transformations kept outside the public attribute facade. */
final class AttributeSetOperations {
    private AttributeSetOperations() {
    }

    static AttributeSet withSystemAttribute(AttributeSet source, String key, Object value) {
        Objects.requireNonNull(key, "key");
        NormalizedAttributeKey normalized = AttributeKey.normalize(key, true);
        CaptureContext context = CaptureContext.currentOrCreate();
        Object capturedValue = ValueCapture.capture(value, context);
        boolean captureTruncated = source.captureTruncated || normalized.truncated() || context.truncated();
        for (int index = 0; index < source.keys.length; index++) {
            if (source.keys[index].equals(normalized.storageKey())) {
                Object[] nextValues = source.values.clone();
                nextValues[index] = capturedValue;
                return new AttributeSet(source.keys.clone(), nextValues, captureTruncated);
            }
        }
        if (source.keys.length < CaptureLimits.MAX_ATTRIBUTES) {
            String[] nextKeys = Arrays.copyOf(source.keys, source.keys.length + 1);
            Object[] nextValues = Arrays.copyOf(source.values, source.values.length + 1);
            nextKeys[source.keys.length] = normalized.storageKey();
            nextValues[source.values.length] = capturedValue;
            return new AttributeSet(nextKeys, nextValues, captureTruncated);
        }

        String[] nextKeys = source.keys.clone();
        Object[] nextValues = source.values.clone();
        int replacement = source.keys.length - 1;
        for (int index = source.keys.length - 1; index >= 0; index--) {
            if (!AttributeSet.isReservedKey(source.keys[index])) {
                replacement = index;
                break;
            }
        }
        nextKeys[replacement] = normalized.storageKey();
        nextValues[replacement] = capturedValue;
        return new AttributeSet(nextKeys, nextValues, true);
    }

    static AttributeSet recapture(AttributeSet source, CaptureContext context) {
        if (source.captureTruncated) {
            context.markTruncated();
        }
        if (source.isEmpty()) {
            return source;
        }
        String[] recapturedKeys = new String[source.keys.length];
        Object[] recapturedValues = new Object[source.values.length];
        int retained = 0;
        for (int index = 0; index < source.keys.length; index++) {
            if (!context.claimEntry()) {
                break;
            }
            recapturedKeys[retained] = context.capturePayloadText(source.keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            recapturedValues[retained] = ValueCapture.capture(source.values[index], context);
            retained++;
        }
        return new AttributeSet(
                Arrays.copyOf(recapturedKeys, retained),
                Arrays.copyOf(recapturedValues, retained),
                source.captureTruncated || context.truncated());
    }
}
