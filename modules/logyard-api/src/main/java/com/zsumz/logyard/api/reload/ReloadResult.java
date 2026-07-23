package com.zsumz.logyard.api.reload;

/** Observable outcome of one digest-based configuration reload attempt. */
public enum ReloadResult {
    /** The configuration bytes are identical to the active snapshot. */
    UNCHANGED,

    /** A changed configuration was validated and committed. */
    APPLIED,

    /** A changed configuration could not be read, validated, assembled, or committed. */
    REJECTED
}
