package com.zsumz.logyard.config;

/** One named event-encoder definition. */
public sealed interface EncoderConfig permits JsonEncoderConfig, ProviderEncoderConfig {
    String name();
}
