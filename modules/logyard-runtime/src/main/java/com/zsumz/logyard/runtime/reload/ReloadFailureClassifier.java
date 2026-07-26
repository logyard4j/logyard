package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.core.runtime.RuntimeReloadDeferredException;
import com.zsumz.logyard.output.json.file.lease.FileLeaseUnavailableException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Converts phase-specific exceptions into stable retry and memoization decisions. */
final class ReloadFailureClassifier {
    private ReloadFailureClassifier() {
    }

    static ReloadFailure source(Throwable failure) {
        ReloadFailureKind kind;
        if (causedBy(failure, FileLeaseUnavailableException.class)
                || causedBy(failure, RuntimeReloadDeferredException.class)) {
            kind = ReloadFailureKind.BUSY;
        } else if (causedBy(failure, IOException.class) || causedBy(failure, UncheckedIOException.class)) {
            kind = ReloadFailureKind.TRANSIENT_RESOURCE;
        } else {
            kind = ReloadFailureKind.INTERNAL_FAILURE;
        }
        return new ReloadFailure(kind, failure);
    }

    static ReloadFailure parsing(Throwable failure) {
        return new ReloadFailure(ReloadFailureKind.INVALID_CANDIDATE, failure);
    }

    static ReloadFailure policy(Throwable failure) {
        ReloadFailureKind kind = failure instanceof RestartRequiredReloadException
                ? ReloadFailureKind.RESTART_REQUIRED
                : ReloadFailureKind.INTERNAL_FAILURE;
        return new ReloadFailure(kind, failure);
    }

    static ReloadFailure assembly(Throwable failure) {
        ReloadFailureKind kind;
        if (causedBy(failure, FileLeaseUnavailableException.class) || causedBy(failure, UncheckedIOException.class)) {
            kind = ReloadFailureKind.TRANSIENT_RESOURCE;
        } else if (causedBy(failure, IllegalArgumentException.class)) {
            kind = ReloadFailureKind.INVALID_CANDIDATE;
        } else {
            kind = ReloadFailureKind.INTERNAL_FAILURE;
        }
        return new ReloadFailure(kind, failure);
    }

    static ReloadFailure publication(Throwable failure) {
        ReloadFailureKind kind = failure instanceof RuntimeReloadDeferredException
                ? ReloadFailureKind.BUSY
                : ReloadFailureKind.INTERNAL_FAILURE;
        return new ReloadFailure(kind, failure);
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
