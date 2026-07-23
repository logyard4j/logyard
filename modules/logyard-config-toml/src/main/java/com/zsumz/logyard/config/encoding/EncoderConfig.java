package com.zsumz.logyard.config.encoding;

/** One named event-encoder definition. */
public sealed interface EncoderConfig permits JsonEncoderConfig, ProviderEncoderConfig {
    String name();
}
