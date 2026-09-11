package com.logyard4j.runtime.reload.coordination;

import com.logyard4j.runtime.reload.WatcherReloadOutcome;

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
        return switch (this) {
            case INVALID_CANDIDATE, RESTART_REQUIRED -> WatcherReloadOutcome.INVALID_CANDIDATE;
            case BUSY -> WatcherReloadOutcome.BUSY_RETRY;
            case TRANSIENT_RESOURCE -> WatcherReloadOutcome.TRANSIENT_RETRY;
            case INTERNAL_FAILURE -> WatcherReloadOutcome.INTERNAL_FAILURE;
        };
    }
}
