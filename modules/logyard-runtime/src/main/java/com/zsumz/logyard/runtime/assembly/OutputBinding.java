package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.spi.output.EventSink;

import java.nio.file.Path;
import java.util.Objects;

/** One assembled output and the evidence needed for transactional reuse. */
record OutputBinding(EventSink sink, OutputSignature signature, Path exclusivePath) {
    OutputBinding {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(signature, "signature");
        exclusivePath = exclusivePath == null ? null : exclusivePath.toAbsolutePath().normalize();
    }
}
