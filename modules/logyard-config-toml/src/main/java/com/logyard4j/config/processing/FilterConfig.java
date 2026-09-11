package com.logyard4j.config.processing;

/** One explicitly named event-filter definition. */
public sealed interface FilterConfig permits
        SamplingFilterConfig,
        RateLimitFilterConfig,
        ProviderFilterConfig {
    String name();
}
