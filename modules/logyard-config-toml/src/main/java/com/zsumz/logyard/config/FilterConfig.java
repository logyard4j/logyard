package com.zsumz.logyard.config;

/** One explicitly named event-filter definition. */
public sealed interface FilterConfig permits
        SamplingFilterConfig,
        RateLimitFilterConfig,
        ProviderFilterConfig {
    String name();
}
