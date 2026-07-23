package com.zsumz.logyard.runtime.reload;

/** Observable outcome of one digest-based reload attempt. */
public enum ReloadResult {
    UNCHANGED,
    APPLIED,
    REJECTED
}
