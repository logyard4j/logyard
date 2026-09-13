package com.logyard4j.logyard.runtime.reload.coordination;

import com.logyard4j.logyard.runtime.reload.WatcherReloadOutcome;

import java.nio.file.Path;

/** Immutable reload result plus the observer notification to deliver after releasing state ownership. */
record ReloadCompletion(
        WatcherReloadOutcome outcome,
        Notification notification,
        String previousDigest,
        String nextDigest,
        Throwable failure) {
    enum Notification {
        NONE,
        UNCHANGED,
        APPLIED,
        REJECTED
    }

    static ReloadCompletion silent(WatcherReloadOutcome outcome) {
        return new ReloadCompletion(outcome, Notification.NONE, null, null, null);
    }

    static ReloadCompletion unchanged(String digest) {
        return new ReloadCompletion(WatcherReloadOutcome.UNCHANGED, Notification.UNCHANGED, digest, digest, null);
    }

    static ReloadCompletion applied(String previousDigest, String nextDigest) {
        return new ReloadCompletion(WatcherReloadOutcome.APPLIED, Notification.APPLIED, previousDigest, nextDigest, null);
    }

    static ReloadCompletion rejected(ReloadFailure failure) {
        return new ReloadCompletion(failure.kind().watcherOutcome(), Notification.REJECTED, null, null, failure.cause());
    }

    void notify(ReloadDiagnosticBoundary diagnostics, String sourceDescription, Path legacyFileSource) {
        if (notification == Notification.NONE) {
            return;
        }
        if (legacyFileSource == null) {
            notifyDescription(diagnostics, sourceDescription);
        } else {
            notifyPath(diagnostics, legacyFileSource);
        }
    }

    private void notifyDescription(ReloadDiagnosticBoundary diagnostics, String source) {
        switch (notification) {
            case UNCHANGED -> diagnostics.unchanged(source, nextDigest);
            case APPLIED -> diagnostics.applied(source, previousDigest, nextDigest);
            case REJECTED -> diagnostics.rejected(source, failure);
            case NONE -> { }
        }
    }

    private void notifyPath(ReloadDiagnosticBoundary diagnostics, Path source) {
        switch (notification) {
            case UNCHANGED -> diagnostics.unchanged(source, nextDigest);
            case APPLIED -> diagnostics.applied(source, previousDigest, nextDigest);
            case REJECTED -> diagnostics.rejected(source, failure);
            case NONE -> { }
        }
    }
}
