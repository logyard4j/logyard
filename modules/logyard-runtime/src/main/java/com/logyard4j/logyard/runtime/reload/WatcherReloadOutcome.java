package com.logyard4j.logyard.runtime.reload;

import com.logyard4j.logyard.api.annotation.InternalApi;
import com.logyard4j.logyard.api.reload.ReloadResult;

/** Watcher-only reload outcome that separates lifecycle contention from candidate rejection. */
@InternalApi
public enum WatcherReloadOutcome {
    /** The latest readable snapshot was already active. */
    UNCHANGED(ReloadResult.UNCHANGED),

    /** A changed snapshot was committed. */
    APPLIED(ReloadResult.APPLIED),

    /** Another lifecycle transition owns the installation; retain dirty state and retry. */
    BUSY_RETRY(ReloadResult.REJECTED),

    /** Reading the source failed transiently; retry with a bounded backoff. */
    TRANSIENT_RETRY(ReloadResult.REJECTED),

    /** An unexpected implementation failure opened the retry circuit until a new source signal arrives. */
    INTERNAL_FAILURE(ReloadResult.REJECTED),

    /** The candidate deterministically failed validation; wait for different content. */
    INVALID_CANDIDATE(ReloadResult.REJECTED);

    private final ReloadResult publicResult;

    WatcherReloadOutcome(ReloadResult publicResult) {
        this.publicResult = publicResult;
    }

    public ReloadResult publicResult() {
        return publicResult;
    }
}
