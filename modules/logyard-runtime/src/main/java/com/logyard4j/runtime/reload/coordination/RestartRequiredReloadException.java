package com.logyard4j.runtime.reload.coordination;

/** Signals a valid configuration change that requires installation-level replacement. */
final class RestartRequiredReloadException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    RestartRequiredReloadException(String message) {
        super(message);
    }
}
