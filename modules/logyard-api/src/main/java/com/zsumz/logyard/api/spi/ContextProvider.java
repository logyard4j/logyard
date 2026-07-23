package com.zsumz.logyard.api.spi;

import com.zsumz.logyard.api.event.AttributeSet;

import java.util.List;

/** Optional caller-thread context capture discovered through ServiceLoader. */
public interface ContextProvider {
    /** Stable provider identifier used in diagnostics. */
    String name();

    /** Captures immutable attributes using the current configuration allowlist. */
    AttributeSet capture(List<String> includedKeys);
}
