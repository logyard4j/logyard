package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.annotation.InternalApi;
import com.zsumz.logyard.api.reload.ReloadResult;

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

    /** The candidate was read but invalid; wait for another filesystem event. */
    WAIT_FOR_CHANGE(ReloadResult.REJECTED);

    private final ReloadResult publicResult;

    WatcherReloadOutcome(ReloadResult publicResult) {
        this.publicResult = publicResult;
    }

    public ReloadResult publicResult() {
        return publicResult;
    }
}
