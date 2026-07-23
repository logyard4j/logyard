package com.zsumz.logyard.runtime.adapter;

/** Process-wide, allocation-free per-thread recursion guard for façade ingress boundaries.
 *
 * <p>All adapter instances share one thread-local state so cross-façade bridge cycles are
 * stopped before they can re-enter Logyard through another Java logging API.</p>
 */
public final class AdapterReentryGuard {
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();

    public boolean enter() {
        if (Boolean.TRUE.equals(ENTERED.get())) {
            return false;
        }
        ENTERED.set(Boolean.TRUE);
        return true;
    }

    public void exit() {
        ENTERED.remove();
    }
}
