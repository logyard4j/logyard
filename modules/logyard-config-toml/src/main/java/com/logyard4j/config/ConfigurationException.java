package com.logyard4j.config;

@SuppressWarnings("serial")
public final class ConfigurationException extends IllegalArgumentException {
    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
