package com.logyard4j.logyard.runtime.management;

/**
 * Base, operational, configured, and effective thresholds for one logger.
 *
 * @param configuredLevel exact runtime override or exact base threshold, or {@code null} when inherited
 * @param effectiveLevel current threshold after runtime overrides and TOML inheritance
 * @param baseConfiguredLevel exact threshold in the active Logyard configuration
 * @param runtimeOverride exact temporary runtime override
 * @param origin source of the effective threshold
 */
public record LoggerLevelSnapshot(
        LoggerLevel configuredLevel,
        LoggerLevel effectiveLevel,
        LoggerLevel baseConfiguredLevel,
        LoggerLevel runtimeOverride,
        LoggerLevelOrigin origin) {

    /**
     * Retains source and binary compatibility with the original two-value management snapshot.
     *
     * @param configuredLevel exact configured threshold
     * @param effectiveLevel effective threshold
     */
    public LoggerLevelSnapshot(LoggerLevel configuredLevel, LoggerLevel effectiveLevel) {
        this(
                configuredLevel,
                effectiveLevel,
                null,
                configuredLevel,
                configuredLevel == null ? LoggerLevelOrigin.INHERITED : LoggerLevelOrigin.RUNTIME_OVERRIDE);
    }
}
