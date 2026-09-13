package com.logyard4j.logyard.output.json.file;

import java.nio.file.Path;

/** Opens one active data file after all nondestructive preparation has succeeded. */
@FunctionalInterface
interface DataFileOpener {
    ActiveDataFile open(Path path, int bufferBytes, boolean append);
}
