package com.zsumz.logyard.config;

public record RotationConfig(long sizeBytes, int keep, String compress) {
}
