package com.logyard4j.core.runtime;

import com.logyard4j.api.annotation.InternalApi;

/**
 * Signals that a valid runtime plan cannot be published yet because temporary lifecycle capacity is unavailable.
 *
 * <p>Callers may retry the same plan after existing output retirements make progress.</p>
 */
@InternalApi
public final class RuntimeReloadDeferredException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public RuntimeReloadDeferredException(String message) {
        super(message);
    }
}
