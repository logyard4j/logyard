package com.zsumz.logyard.runtime.reload;

import java.io.IOException;

/** Internal source reader used by explicit reload and optional file watching. */
@FunctionalInterface
public interface ConfigurationSnapshotReader {
    ConfigurationSnapshot read() throws IOException;
}
