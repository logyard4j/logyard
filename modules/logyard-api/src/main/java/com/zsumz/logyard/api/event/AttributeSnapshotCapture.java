package com.zsumz.logyard.api.event;

import java.util.Arrays;

/** Converts mutable attribute assembly storage into one bounded immutable snapshot. */
final class AttributeSnapshotCapture {
    private AttributeSnapshotCapture() {
    }

    static AttributeSet capture(
            String[] keys,
            String[] normalizedKeyIdentities,
            Object[] values,
            int size,
            boolean assemblyTruncated) {
        CaptureContext context = CaptureContext.currentOrCreate();
        String[] snapshotKeys = new String[size];
        Object[] snapshotValues = new Object[size];
        String[] snapshotIdentities = normalizedKeyIdentities == null ? null : new String[size];
        int retained = 0;
        for (int index = 0; index < size; index++) {
            if (context.remainingPayloadCharacters() < keys[index].length()) {
                context.markTruncated();
                break;
            }
            if (!context.claimEntry()) {
                break;
            }
            snapshotKeys[retained] = context.capturePayloadText(keys[index], CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS);
            if (snapshotIdentities != null) {
                snapshotIdentities[retained] = normalizedKeyIdentities[index];
            }
            snapshotValues[retained] = ValueCapture.capture(values[index], context);
            retained++;
        }
        return new AttributeSet(
                Arrays.copyOf(snapshotKeys, retained),
                snapshotIdentities == null ? null : Arrays.copyOf(snapshotIdentities, retained),
                Arrays.copyOf(snapshotValues, retained),
                assemblyTruncated || context.truncated());
    }
}
