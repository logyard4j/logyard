package com.logyard4j.logyard.output.json.file.lease;

import java.nio.file.Path;

/** Temporary failure to acquire exclusive ownership of a JSON output path. */
public final class FileLeaseUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    FileLeaseUnavailableException(Path path, boolean sameProcess, Throwable cause) {
        super("Logyard output is already owned" + (sameProcess ? " in this JVM" : "") + ": " + path, cause);
    }
}
