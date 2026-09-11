package com.logyard4j.api.spi.context;

import com.logyard4j.api.event.AttributeSet;

import java.util.List;

/**
 * Optional caller-thread context capture discovered through ServiceLoader.
 *
 * <p>One provider instance may be called concurrently by unrelated publication threads and must be
 * thread-safe. Capture must be bounded, must not perform unbounded blocking work, and should not log
 * recursively. Context providers have no managed close callback, so an implementation must not own
 * resources that require lifecycle cleanup or retain application objects after an invocation.</p>
 */
public interface ContextProvider {
    /**
     * Returns the stable provider identifier used in diagnostics.
     *
     * @return provider name
     */
    String name();

    /**
     * Captures immutable attributes using the current configuration allowlist.
     *
     * @param includedKeys provider-neutral keys requested by configuration
     * @return detached, immutable attributes
     */
    AttributeSet capture(List<String> includedKeys);
}
