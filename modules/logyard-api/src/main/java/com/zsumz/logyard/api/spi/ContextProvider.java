package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.AttributeSet;

import java.util.List;

/** Optional caller-thread context capture discovered through ServiceLoader. */
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
