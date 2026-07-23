package com.zsumz.logyard.config;

public record TextStyleConfig(
        String foreground,
        String background,
        Boolean bold,
        Boolean dim,
        Boolean italic,
        Boolean underline) {
}
