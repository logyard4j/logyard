package com.logyard4j.runtime.assembly.output;

import com.logyard4j.api.spi.output.EventSink;

import java.nio.file.Path;
import java.util.Objects;

/** One assembled output and the evidence needed for transactional reuse. */
public record OutputBinding(EventSink sink, OutputSignature signature, Path exclusivePath) {
    public OutputBinding {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(signature, "signature");
        exclusivePath = exclusivePath == null ? null : exclusivePath.toAbsolutePath().normalize();
    }
}
