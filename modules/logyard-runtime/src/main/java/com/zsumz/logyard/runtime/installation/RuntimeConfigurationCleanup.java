package com.zsumz.logyard.runtime.installation;

import com.zsumz.logyard.runtime.assembly.RuntimeAssembly;

/** Rollback cleanup for an uncommitted configuration handoff. */
final class RuntimeConfigurationCleanup {
    private RuntimeConfigurationCleanup() {
    }

    static void closeReplacement(
            ActiveRuntimeConfiguration replacement,
            RuntimeAssembly candidate,
            RuntimeAssembly current,
            Throwable failure) {
        if (replacement != null) {
            try {
                replacement.closeWatcher();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (candidate != null) {
            candidate.closeCandidateOutputs(current, failure);
        }
    }
}
