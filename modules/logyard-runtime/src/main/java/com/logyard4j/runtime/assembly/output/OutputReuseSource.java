package com.logyard4j.runtime.assembly.output;

import java.nio.file.Path;

/** Read-only binding lookup required to reuse outputs during candidate assembly. */
public interface OutputReuseSource {
    OutputBinding binding(String outputName);

    OutputBinding bindingForExclusivePath(Path path);
}
