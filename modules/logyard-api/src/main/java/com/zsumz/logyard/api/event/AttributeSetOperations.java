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
            if (source.keys[index].equals(normalized.storageKey())
                    && normalizedKeyIdentity(source, index) == null) {
                Object[] nextValues = source.values.clone();
                nextValues[index] = capturedValue;
                return new AttributeSet(
                        source.keys.clone(),
                        cloneIdentities(source),
                        nextValues,
                        captureTruncated);
            }
        }
        if (source.keys.length < CaptureLimits.MAX_ATTRIBUTES) {
            String[] nextKeys = Arrays.copyOf(source.keys, source.keys.length + 1);
            String[] nextIdentities = extendIdentities(source, normalized.truncated());
            Object[] nextValues = Arrays.copyOf(source.values, source.values.length + 1);
            boolean collision = relocateNormalizedCollision(source, nextKeys, nextIdentities, normalized.storageKey());
            nextKeys[source.keys.length] = normalized.storageKey();
            if (nextIdentities != null) {
                nextIdentities[source.keys.length] = normalized.truncated() ? normalized.storageKey() : null;
            }
            nextValues[source.values.length] = capturedValue;
            return new AttributeSet(
                    nextKeys,
                    nextIdentities,
                    nextValues,
                    captureTruncated || collision);
        }

        String[] nextKeys = source.keys.clone();
        String[] nextIdentities = source.normalizedKeyIdentities == null && normalized.truncated()
                ? new String[source.keys.length]
                : cloneIdentities(source);
        Object[] nextValues = source.values.clone();
        int replacement = source.keys.length - 1;
        for (int index = source.keys.length - 1; index >= 0; index--) {
            if (!AttributeSet.isReservedKey(source.keys[index])) {
                replacement = index;
                break;
            }
        }
        nextKeys[replacement] = normalized.storageKey();
        if (nextIdentities != null) {
            nextIdentities[replacement] = normalized.truncated() ? normalized.storageKey() : null;
        }
        nextValues[replacement] = capturedValue;
        return new AttributeSet(nextKeys, nextIdentities, nextValues, true);
    }

    static AttributeSet recapture(AttributeSet source, CaptureContext context) {
        if (source.captureTruncated) {
            context.markTruncated();
        }
        if (source.isEmpty()) {
            return source;
        }
        String[] recapturedKeys = new String[source.keys.length];
        String[] recapturedIdentities = source.normalizedKeyIdentities == null
                ? null
                : new String[source.keys.length];
        Object[] recapturedValues = new Object[source.values.length];
        int retained = 0;
        for (int index = 0; index < source.keys.length; index++) {
            if (context.remainingPayloadCharacters() < source.keys[index].length()) {
                context.markTruncated();
                break;
            }
            if (!context.claimEntry()) {
                break;
            }
            recapturedKeys[retained] = context.capturePayloadText(source.keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            if (recapturedIdentities != null) {
                recapturedIdentities[retained] = source.normalizedKeyIdentities[index];
            }
            recapturedValues[retained] = ValueCapture.capture(source.values[index], context);
            retained++;
        }
        return new AttributeSet(
                Arrays.copyOf(recapturedKeys, retained),
                recapturedIdentities == null ? null : Arrays.copyOf(recapturedIdentities, retained),
                Arrays.copyOf(recapturedValues, retained),
                source.captureTruncated || context.truncated());
    }

    private static String[] cloneIdentities(AttributeSet source) {
        return source.normalizedKeyIdentities == null ? null : source.normalizedKeyIdentities.clone();
    }

    private static String[] extendIdentities(AttributeSet source, boolean normalizedKey) {
        if (source.normalizedKeyIdentities == null) {
            return normalizedKey ? new String[source.keys.length + 1] : null;
        }
        return Arrays.copyOf(source.normalizedKeyIdentities, source.keys.length + 1);
    }

    private static boolean relocateNormalizedCollision(
            AttributeSet source,
            String[] nextKeys,
            String[] nextIdentities,
            String literalKey) {
        for (int index = 0; index < source.keys.length; index++) {
            String identity = normalizedKeyIdentity(source, index);
            if (!source.keys[index].equals(literalKey) || identity == null) {
                continue;
            }
            if (nextIdentities == null) {
                throw new IllegalStateException("normalized attribute identity is unavailable");
            }
            for (int collision = 2; ; collision++) {
                String candidate = CaptureLimits.disambiguateAttributeKey(identity, collision);
                if (!contains(nextKeys, source.keys.length, candidate)) {
                    nextKeys[index] = candidate;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String[] keys, int length, String candidate) {
        for (int index = 0; index < length; index++) {
            if (candidate.equals(keys[index])) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedKeyIdentity(AttributeSet source, int index) {
        return source.normalizedKeyIdentities == null ? null : source.normalizedKeyIdentities[index];
    }
}
