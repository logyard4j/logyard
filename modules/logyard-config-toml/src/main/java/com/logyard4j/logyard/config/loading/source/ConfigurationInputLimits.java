package com.logyard4j.logyard.config.loading.source;

/** Shared upper bounds for bytes and expanded text accepted from configuration sources. */
public final class ConfigurationInputLimits {
    public static final int MAX_CONFIG_BYTES = 1_024 * 1_024;
    public static final int MAX_EXPANDED_STRING_CHARS = 65_536;

    private ConfigurationInputLimits() {
    }
}
