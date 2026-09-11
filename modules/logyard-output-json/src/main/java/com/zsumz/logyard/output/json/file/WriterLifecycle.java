package com.zsumz.logyard.output.json.file;

import java.nio.file.Path;

/** Small state machine for prepared, usable, failed, and closed writer lifecycles. */
final class WriterLifecycle {
    enum State {
        PREPARED,
        OPEN,
        FAILED,
        CLOSED
    }

    private State state = State.PREPARED;
    private Throwable failure;

    State state() {
        return state;
    }

    Throwable failure() {
        return failure;
    }

    void opened() {
        state = State.OPEN;
        failure = null;
    }

    void failed(Throwable cause) {
        state = State.FAILED;
        failure = cause;
    }

    void recoverableOperationFailed(Throwable cause) {
        failure = cause;
    }

    void operationSucceeded() {
        failure = null;
    }

    void closed() {
        state = State.CLOSED;
    }

    void closeFailed(Throwable cause) {
        failure = cause;
        state = State.CLOSED;
    }

    void requireUsable(Path path) {
        if (state == State.FAILED) {
            throw new IllegalStateException("Logyard JSON output failed: " + path, failure);
        }
        if (state == State.CLOSED) {
            throw new IllegalStateException("Logyard JSON output is closed: " + path);
        }
    }
}
