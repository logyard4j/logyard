package com.logyard4j.api.event;

import com.logyard4j.api.annotation.InternalApi;

/** Preserves detached attribute identity and diagnostics across deferred publication. */
@InternalApi
public final class CapturedAttributeAccess {
    private CapturedAttributeAccess() {
    }

    /** Copies an entry without treating reserved diagnostic names as caller input.
     * @param destination destination builder
     * @param source detached source
     * @param index source entry index
     */
    public static void copyEntry(AttributeSet.Builder destination, AttributeSet source, int index) {
        destination.state.putCaptured(source, index);
    }

    /** Replaces a retained value without changing its captured key identity.
     * @param destination builder containing the copied source entry
     * @param key exact captured storage key
     * @param value replacement to capture
     */
    public static void replaceValue(AttributeSet.Builder destination, String key, Object value) {
        destination.state.replaceCapturedValue(key, value);
    }

    /** Reports whether an entry was derived from a shortened key.
     * @param source detached source
     * @param index source entry index
     * @return whether the stored key represents a different original key
     */
    public static boolean normalizedKey(AttributeSet source, int index) {
        return source.normalizedKeyIdentities != null && source.normalizedKeyIdentities[index] != null;
    }

    /** Reports capture loss even when the source has no retained entries.
     * @param source detached source
     * @return whether content was omitted
     */
    public static boolean truncated(AttributeSet source) {
        return source.captureTruncated;
    }
}
