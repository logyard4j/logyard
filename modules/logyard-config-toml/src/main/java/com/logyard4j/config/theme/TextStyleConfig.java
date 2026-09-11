package com.logyard4j.config.theme;

public record TextStyleConfig(
        String foreground,
        String background,
        Boolean bold,
        Boolean dim,
        Boolean italic,
        Boolean underline) {
}
