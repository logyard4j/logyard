package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.Objects;

/** Trusted immutable transformations kept outside the public attribute facade. */
final class AttributeSetOperations {
    private AttributeSetOperations() {
    }

    static AttributeSet withSystemAttribute(AttributeSet source, String key, Object value) {
        Objects.requireNonNull(key, "key");
        String normalized = CaptureLimits.attributeKey(key);
        Object capturedValue = ValueCapture.capture(value, CaptureContext.currentOrCreate());
        for (int index = 0; index < source.keys.length; index++) {
            if (source.keys[index].equals(normalized)) {
                Object[] nextValues = source.values.clone();
                nextValues[index] = capturedValue;
                return new AttributeSet(source.keys.clone(), nextValues);
            }
        }
        if (source.keys.length < CaptureLimits.MAX_ATTRIBUTES) {
            String[] nextKeys = Arrays.copyOf(source.keys, source.keys.length + 1);
            Object[] nextValues = Arrays.copyOf(source.values, source.values.length + 1);
            nextKeys[source.keys.length] = normalized;
            nextValues[source.values.length] = capturedValue;
            return new AttributeSet(nextKeys, nextValues);
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
        nextKeys[replacement] = normalized;
        nextValues[replacement] = capturedValue;
        return new AttributeSet(nextKeys, nextValues);
    }

    static AttributeSet recapture(AttributeSet source, CaptureContext context) {
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
            recapturedKeys[retained] = context.captureText(source.keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            recapturedValues[retained] = ValueCapture.capture(source.values[index], context);
            retained++;
        }
        if (retained == 0) {
            return AttributeSet.EMPTY;
        }
        return new AttributeSet(Arrays.copyOf(recapturedKeys, retained), Arrays.copyOf(recapturedValues, retained));
    }
}
