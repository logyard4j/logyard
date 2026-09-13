package com.logyard4j.logyard.config.theme;

public record TextStyleConfig(
        String foreground,
        String background,
        Boolean bold,
        Boolean dim,
        Boolean italic,
        Boolean underline) {
}
