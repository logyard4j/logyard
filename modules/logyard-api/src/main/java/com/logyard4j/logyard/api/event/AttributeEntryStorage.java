package com.logyard4j.logyard.api.event;

import java.util.function.Supplier;

/** Applies exact-key replacement and bounded normalized-key collision policy to mutable attribute entries. */
final class AttributeEntryStorage {
    private static final String TRUNCATION_KEY = SystemAttributes.ATTRIBUTES_TRUNCATED;

    private final AttributeEntrySlots slots;
    private int size;
    private boolean captureTruncated;

    AttributeEntryStorage(int expectedSize) {
        slots = new AttributeEntrySlots(expectedSize);
    }

    int size() {
        return size;
    }

    boolean isFull() {
        return size >= CaptureLimits.MAX_ATTRIBUTES - 1;
    }

    boolean captureTruncated() {
        return captureTruncated;
    }

    void markCaptureTruncated() {
        captureTruncated = true;
    }

    boolean put(String original, String canonicalKey, boolean keyTruncated, Object value) {
        int existingOriginal = indexOfOriginal(original, canonicalKey, keyTruncated);
        if (existingOriginal >= 0) {
            slots.value(existingOriginal, value);
            return true;
        }
        String storageKey = resolveNewStorageKey(canonicalKey, keyTruncated);
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            slots.value(existing, value);
            return true;
        }
        if (isFull()) {
            return false;
        }
        append(storageKey, original, keyTruncated ? canonicalKey : null, value);
        captureTruncated |= keyTruncated;
        return true;
    }

    boolean putCaptured(String key, String normalizedIdentity, Object value) {
        String storageKey = resolveCapturedStorageKey(key, normalizedIdentity);
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            slots.value(existing, value);
            return true;
        }
        if (isFull()) {
            return false;
        }
        append(storageKey, storageKey, normalizedIdentity, value);
        captureTruncated |= normalizedIdentity != null || !storageKey.equals(key);
        return true;
    }

    void replaceCapturedValue(String key, Object value) {
        int index = indexOf(key);
        if (index < 0) throw new IllegalArgumentException("captured attribute is absent: " + key);
        slots.value(index, value);
    }

    boolean replaceOriginal(String original, String canonicalKey, boolean keyTruncated, Supplier<?> supplier) {
        int existingOriginal = indexOfOriginal(original, canonicalKey, keyTruncated);
        if (existingOriginal < 0) {
            return false;
        }
        slots.value(existingOriginal, supplier.get());
        return true;
    }

    void putTruncationMarker() {
        int existing = indexOf(TRUNCATION_KEY);
        if (existing >= 0) {
            slots.value(existing, true);
        } else if (size < CaptureLimits.MAX_ATTRIBUTES) {
            append(TRUNCATION_KEY, TRUNCATION_KEY, null, true);
        }
    }

    String[] keys() {
        return slots.keys();
    }

    String[] normalizedKeyIdentities() {
        return slots.normalizedKeyIdentities();
    }

    Object[] values() {
        return slots.values();
    }

    private void append(String key, String original, String normalizedIdentity, Object value) {
        slots.append(size, key, original, normalizedIdentity, value);
        size++;
    }

    private int indexOf(String key) {
        for (int index = 0; index < size; index++) {
            if (slots.key(index).equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private int indexOfOriginal(String original, String canonicalKey, boolean keyTruncated) {
        int canonical = indexOf(canonicalKey);
        if (canonical < 0) {
            return canonical;
        }
        if (!keyTruncated && slots.normalizedIdentity(canonical) != null) {
            // A captured shortened key has no original identity; its spelling is not an exact caller key.
            return -1;
        }
        if (!slots.tracksOriginals()) {
            return keyTruncated ? -1 : canonical;
        }
        for (int index = 0; index < size; index++) {
            if (original.equals(slots.original(index))) {
                return index;
            }
        }
        return -1;
    }

    private String resolveNewStorageKey(String canonicalKey, boolean keyTruncated) {
        int canonical = indexOf(canonicalKey);
        if (canonical < 0) {
            return canonicalKey;
        }
        slots.ensureIdentityStorage(size);
        if (!keyTruncated && slots.normalizedIdentity(canonical) != null) {
            relocateNormalizedEntry(canonical);
            return canonicalKey;
        }
        markCaptureTruncated();
        return uniqueCollisionKey(canonicalKey);
    }

    private String resolveCapturedStorageKey(String storageKey, String normalizedIdentity) {
        int occupied = indexOf(storageKey);
        if (occupied < 0) {
            return storageKey;
        }
        if (normalizedIdentity == null && slots.normalizedIdentity(occupied) == null) {
            return storageKey;
        }
        slots.ensureIdentityStorage(size);
        if (normalizedIdentity == null) {
            relocateNormalizedEntry(occupied);
            return storageKey;
        }
        markCaptureTruncated();
        return uniqueCollisionKey(normalizedIdentity);
    }

    private void relocateNormalizedEntry(int index) {
        String normalizedIdentity = slots.normalizedIdentity(index);
        if (normalizedIdentity != null) {
            slots.key(index, uniqueCollisionKey(normalizedIdentity));
            markCaptureTruncated();
        }
    }

    private String uniqueCollisionKey(String canonicalKey) {
        for (int collision = 2; ; collision++) {
            String candidate = CaptureLimits.disambiguateAttributeKey(canonicalKey, collision);
            if (indexOf(candidate) < 0) {
                return candidate;
            }
        }
    }
}
