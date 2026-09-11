package com.logyard4j.api.event;

import com.logyard4j.api.annotation.InternalApi;

import java.util.Objects;

/**
 * Validated attribute-key identity and its bounded storage representation.
 *
 * @param original original caller key retained only while assembling attributes
 * @param storageKey bounded key written to the immutable attribute set
 * @param truncated whether normalization omitted source characters
 * @hidden
 */
@InternalApi
public record NormalizedAttributeKey(String original, String storageKey, boolean truncated) {
    /** Validates the original and storage representations. */
    public NormalizedAttributeKey {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(storageKey, "storageKey");
    }
}
