package com.zsumz.logyard.runtime.reload;

/** Operational meaning of a failed reload phase. */
enum ReloadFailureKind {
    INVALID_CANDIDATE(true),
    RESTART_REQUIRED(true),
    BUSY(false),
    TRANSIENT_RESOURCE(false),
    INTERNAL_FAILURE(false);

    private final boolean deterministicForDigest;

    ReloadFailureKind(boolean deterministicForDigest) {
        this.deterministicForDigest = deterministicForDigest;
    }

    boolean deterministicForDigest() {
        return deterministicForDigest;
    }

    WatcherReloadOutcome watcherOutcome() {
        return deterministicForDigest ? WatcherReloadOutcome.INVALID_CANDIDATE : WatcherReloadOutcome.TRANSIENT_RETRY;
    }
}
