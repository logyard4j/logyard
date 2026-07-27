package com.zsumz.logyard.runtime.reload.coordination;

import com.zsumz.logyard.config.LogyardConfig;

/** Enforces the first-release boundary between in-place routing reloads and installation-owned lifecycle policy. */
final class RuntimeReloadPolicy {
    private RuntimeReloadPolicy() {
    }

    static void requireSupportedChanges(LogyardConfig active, LogyardConfig candidate) {
        if (!active.runtime().equals(candidate.runtime())) {
            throw new RestartRequiredReloadException(
                    "changes to [runtime] watch, reload_debounce, shutdown_timeout, or internal_status "
                            + "require an application/framework configuration handoff or process restart");
        }
        if (!active.context().mdc().equals(candidate.context().mdc())) {
            throw new RestartRequiredReloadException(
                    "changes to context.mdc require an application/framework configuration handoff or process restart");
        }
    }
}
