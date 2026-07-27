package com.zsumz.logyard.api.event;

import java.util.Arrays;
import java.util.function.Supplier;

/** Collision-aware mutable storage for the entries being assembled into one attribute set. */
final class AttributeEntryStorage {
    private String[] keys;
    private String[] originals;
    private String[] normalizedKeyIdentities;
    private Object[] values;
    private int size;
    private boolean captureTruncated;

    AttributeEntryStorage(int expectedSize) {
        int capacity = Math.max(1, Math.min(expectedSize, CaptureLimits.MAX_ATTRIBUTES));
        keys = new String[capacity];
        values = new Object[capacity];
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
            values[existingOriginal] = value;
            return true;
        }
        String storageKey = resolveNewStorageKey(canonicalKey, keyTruncated);
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            values[existing] = value;
            return true;
        }
        if (isFull()) {
            return false;
        }
        ensureCapacity(size + 1);
        keys[size] = storageKey;
        if (keyTruncated) {
            ensureKeyIdentityStorage();
            normalizedKeyIdentities[size] = canonicalKey;
        }
        if (originals != null) {
            originals[size] = original;
        }
        values[size] = value;
        size++;
        captureTruncated |= keyTruncated;
        return true;
    }

    boolean putCaptured(String key, String normalizedIdentity, Object value) {
        String storageKey = resolveCapturedStorageKey(key, normalizedIdentity);
        int existing = indexOf(storageKey);
        if (existing >= 0) {
            values[existing] = value;
            return true;
        }
        if (isFull()) {
            return false;
        }
        ensureCapacity(size + 1);
        keys[size] = storageKey;
        if (normalizedIdentity != null) {
            ensureKeyIdentityStorage();
            normalizedKeyIdentities[size] = normalizedIdentity;
        }
        if (originals != null) {
            originals[size] = storageKey;
        }
        values[size] = value;
        size++;
        captureTruncated |= normalizedIdentity != null || !storageKey.equals(key);
        return true;
    }

    boolean replaceOriginal(String original, String canonicalKey, boolean keyTruncated, Supplier<?> supplier) {
        int existingOriginal = indexOfOriginal(original, canonicalKey, keyTruncated);
        if (existingOriginal < 0) {
            return false;
        }
        values[existingOriginal] = supplier.get();
        return true;
    }

    void putTruncationMarker() {
        String key = "logyard.attributes.truncated";
        int existing = indexOf(key);
        if (existing >= 0) {
            values[existing] = true;
            return;
        }
        if (size < CaptureLimits.MAX_ATTRIBUTES) {
            ensureCapacity(size + 1);
            keys[size] = key;
            if (originals != null) {
                originals[size] = key;
            }
            if (normalizedKeyIdentities != null) {
                normalizedKeyIdentities[size] = null;
            }
            values[size] = true;
            size++;
        }
    }

    String[] keys() {
        return keys;
    }

    String[] normalizedKeyIdentities() {
        return normalizedKeyIdentities;
    }

    Object[] values() {
        return values;
    }

    private int indexOf(String key) {
        for (int index = 0; index < size; index++) {
            if (keys[index].equals(key)) {
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
        if (originals == null) {
            return keyTruncated ? -1 : canonical;
        }
        for (int index = 0; index < size; index++) {
            if (original.equals(originals[index])) {
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
        ensureKeyIdentityStorage();
        if (!keyTruncated && normalizedKeyIdentities[canonical] != null) {
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
        if (normalizedIdentity == null && normalizedKeyIdentity(occupied) == null) {
            return storageKey;
        }
        ensureKeyIdentityStorage();
        if (normalizedIdentity == null) {
            relocateNormalizedEntry(occupied);
            return storageKey;
        }
        markCaptureTruncated();
        return uniqueCollisionKey(normalizedIdentity);
    }

    private void relocateNormalizedEntry(int index) {
        String normalizedIdentity = normalizedKeyIdentity(index);
        if (normalizedIdentity == null) {
            return;
        }
        keys[index] = uniqueCollisionKey(normalizedIdentity);
        markCaptureTruncated();
    }

    private String uniqueCollisionKey(String canonicalKey) {
        for (int collision = 2; ; collision++) {
            String candidate = CaptureLimits.disambiguateAttributeKey(canonicalKey, collision);
            if (indexOf(candidate) < 0) {
                return candidate;
            }
        }
    }

    private String normalizedKeyIdentity(int index) {
        return normalizedKeyIdentities == null ? null : normalizedKeyIdentities[index];
    }

    private void ensureCapacity(int needed) {
        if (needed <= keys.length) {
            return;
        }
        int next = Math.min(CaptureLimits.MAX_ATTRIBUTES, Math.max(needed, keys.length << 1));
        keys = Arrays.copyOf(keys, next);
        if (originals != null) {
            originals = Arrays.copyOf(originals, next);
        }
        if (normalizedKeyIdentities != null) {
            normalizedKeyIdentities = Arrays.copyOf(normalizedKeyIdentities, next);
        }
        values = Arrays.copyOf(values, next);
    }

    private void ensureKeyIdentityStorage() {
        if (originals == null) {
            originals = new String[keys.length];
            System.arraycopy(keys, 0, originals, 0, size);
        }
        if (normalizedKeyIdentities == null) {
            normalizedKeyIdentities = new String[keys.length];
        }
    }
}
