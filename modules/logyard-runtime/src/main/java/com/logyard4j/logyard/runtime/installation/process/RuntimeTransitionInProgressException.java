package com.logyard4j.logyard.runtime.installation.process;

import com.logyard4j.logyard.api.annotation.InternalApi;

/**
 * Retryable signal that a runtime acquisition could not commit during an active lifecycle transition.
 *
 * <p>Logging façade adapters must treat this as temporary unavailability rather than a terminal
 * initialization failure.</p>
 */
@InternalApi
public final class RuntimeTransitionInProgressException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    RuntimeTransitionInProgressException(String message) {
        super(message);
    }
}
