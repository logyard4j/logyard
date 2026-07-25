package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.config.runtime.RuntimeConfig;

/** Enforces the first-release boundary between in-place routing reloads and installation-owned lifecycle policy. */
final class RuntimeReloadPolicy {
    private RuntimeReloadPolicy() {
    }

    static void requireInstallationPolicyUnchanged(RuntimeConfig active, RuntimeConfig candidate) {
        if (!active.equals(candidate)) {
            throw new RestartRequiredReloadException(
                    "changes to [runtime] watch, reload_debounce, shutdown_timeout, or internal_status "
                            + "require an application/framework configuration handoff or process restart");
        }
    }
}
