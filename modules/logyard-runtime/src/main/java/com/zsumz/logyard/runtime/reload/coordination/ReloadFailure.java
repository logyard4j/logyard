package com.zsumz.logyard.runtime.reload.coordination;

import java.util.Objects;

/** Classified reload failure with its original diagnostic cause. */
record ReloadFailure(ReloadFailureKind kind, Throwable cause) {
    ReloadFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cause, "cause");
    }
}
