package com.zsumz.logyard.config.output;

public record RotationConfig(long sizeBytes, int keep, String compress) {
}
