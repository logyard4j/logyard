package com.logyard4j.config.output;

import java.time.Duration;

/**
 * File-rotation limits for one file output.
 *
 * <p>{@code sizeBytes} always carries a value because the decoder defaults it; {@code interval} is
 * optional and, when present, caps how long one active data file may stay open. The two limits are
 * independent: whichever is reached first rotates the file.</p>
 */
public record RotationConfig(long sizeBytes, int keep, String compress, Duration interval) {
    public RotationConfig(long sizeBytes, int keep, String compress) {
        this(sizeBytes, keep, compress, null);
    }
}
