package com.zsumz.logyard.runtime.reload;

/** Remembers only failures that are deterministic for one exact content digest. */
final class RejectedSnapshotMemo {
    private volatile String digest;

    boolean contains(String candidateDigest) {
        return candidateDigest.equals(digest);
    }

    void record(String candidateDigest, ReloadFailureKind kind) {
        if (kind.deterministicForDigest()) {
            digest = candidateDigest;
        }
    }

    void clear() {
        digest = null;
    }
}
