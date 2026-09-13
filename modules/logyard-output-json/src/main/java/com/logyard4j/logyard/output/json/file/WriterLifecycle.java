package com.logyard4j.logyard.output.json.file;

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
    private volatile Snapshot snapshot = new Snapshot(State.PREPARED, null);

    Snapshot snapshot() {
        return snapshot;
    }

    State state() {
        return state;
    }

    Throwable failure() {
        return failure;
    }

    void opened() {
        state = State.OPEN;
        failure = null;
        publish();
    }

    void failed(Throwable cause) {
        state = State.FAILED;
        failure = cause;
        publish();
    }

    void recoverableOperationFailed(Throwable cause) {
        failure = cause;
        publish();
    }

    void operationSucceeded() {
        if (failure != null) {
            failure = null;
            publish();
        }
    }

    void closed() {
        state = State.CLOSED;
        publish();
    }

    void closeFailed(Throwable cause) {
        failure = cause;
        state = State.CLOSED;
        publish();
    }

    private void publish() {
        snapshot = new Snapshot(state, failure == null ? null : failure.getClass().getName());
    }

    record Snapshot(State state, String failureType) {
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
