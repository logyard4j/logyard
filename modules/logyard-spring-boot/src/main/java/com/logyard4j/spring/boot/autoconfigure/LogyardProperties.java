package com.logyard4j.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Minimal Spring property surface for selecting Logyard configuration.
 *
 * <p>Routing, processing, output, and delivery configuration remain authoritative in TOML.</p>
 */
@ConfigurationProperties("logyard")
public final class LogyardProperties {
    private boolean enabled = true;
    private String config;
    private boolean required;

    /** Returns whether Spring-owned Logyard beans are enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Enables or disables Spring-owned Logyard beans. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Returns the optional classpath or filesystem configuration location. */
    public String getConfig() {
        return config;
    }

    /** Selects a classpath or filesystem configuration location. */
    public void setConfig(String config) {
        this.config = config;
    }

    /** Returns whether startup must fail when no configuration can be discovered. */
    public boolean isRequired() {
        return required;
    }

    /** Requires a selected, classpath, or working-directory configuration. */
    public void setRequired(boolean required) {
        this.required = required;
    }
}
