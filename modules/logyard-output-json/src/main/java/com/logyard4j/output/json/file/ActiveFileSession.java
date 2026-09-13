package com.logyard4j.output.json.file;

import java.util.Objects;

/** Owns the current data file and makes uncertain active-file I/O terminal. */
final class ActiveFileSession {
    private final WriterLifecycle lifecycle;
    private ActiveDataFile file;

    ActiveFileSession(WriterLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    boolean present() {
        return file != null;
    }

    long logicalBytes() {
        return requireFile().logicalBytes();
    }

    void attach(ActiveDataFile opened) {
        if (file != null) {
            throw new IllegalStateException("an active Logyard data file is already attached");
        }
        file = Objects.requireNonNull(opened, "opened");
    }

    ActiveDataFile detach() {
        ActiveDataFile detached = requireFile();
        file = null;
        return detached;
    }

    void write(byte[] record, int length, byte terminator) {
        try {
            requireFile().write(record, length, terminator);
            lifecycle.operationSucceeded();
        } catch (RuntimeException failure) {
            fail(failure);
            throw failure;
        }
    }

    void flush() {
        try {
            requireFile().flush();
            lifecycle.operationSucceeded();
        } catch (RuntimeException failure) {
            fail(failure);
            throw failure;
        }
    }

    private void fail(RuntimeException failure) {
        ActiveDataFile failedFile = file;
        file = null;
        lifecycle.failed(failure);
        if (failedFile != null) {
            FileWriterInitialization.closeAfterFailure(failure, failedFile::close);
        }
    }

    private ActiveDataFile requireFile() {
        return Objects.requireNonNull(file, "active Logyard data file");
    }
}
