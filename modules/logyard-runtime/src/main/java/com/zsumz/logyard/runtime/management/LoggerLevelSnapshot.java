package com.zsumz.logyard.runtime.management;

/**
 * Configured and effective operational thresholds for one logger.
 *
 * @param configuredLevel explicit runtime override, or {@code null} when inherited
 * @param effectiveLevel current threshold after runtime overrides and TOML inheritance
 */
public record LoggerLevelSnapshot(
        LoggerLevel configuredLevel,
        LoggerLevel effectiveLevel) {
}
