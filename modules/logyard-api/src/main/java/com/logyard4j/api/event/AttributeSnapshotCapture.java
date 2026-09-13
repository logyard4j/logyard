package com.logyard4j.api.event;

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
        if (retained != size) {
            snapshotKeys = Arrays.copyOf(snapshotKeys, retained);
            snapshotIdentities = snapshotIdentities == null ? null : Arrays.copyOf(snapshotIdentities, retained);
            snapshotValues = Arrays.copyOf(snapshotValues, retained);
        }
        return new AttributeSet(snapshotKeys, snapshotIdentities, snapshotValues, assemblyTruncated || context.truncated());
    }
}
