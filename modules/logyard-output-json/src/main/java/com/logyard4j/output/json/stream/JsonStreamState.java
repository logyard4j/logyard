package com.logyard4j.output.json.stream;

import com.logyard4j.api.diagnostics.HealthStatus;

/** Sticky lifecycle and first-failure record for one JSON stream transport. */
final class JsonStreamState {
    private Stage stage = Stage.OPEN;
    private Throwable failure;
    private volatile Snapshot snapshot = new Snapshot(HealthStatus.HEALTHY, null);

    void requireOpen() {
        if (stage == Stage.FAILED || stage == Stage.CLOSING_FAILED || stage == Stage.CLOSED_FAILED) {
            throw new IllegalStateException("Logyard JSON stream output failed", failure);
        }
        if (stage == Stage.CLOSING || stage == Stage.CLOSED) {
            throw new IllegalStateException("Logyard JSON stream output is closed");
        }
    }

    void failed(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        stage = switch (stage) {
            case OPEN -> Stage.FAILED;
            case FAILED -> Stage.FAILED;
            case CLOSING, CLOSING_FAILED, CLOSED, CLOSED_FAILED -> Stage.CLOSED_FAILED;
        };
        publish();
    }

    boolean failed() {
        return failure != null;
    }

    Throwable failure() {
        return failure;
    }

    Snapshot snapshot() {
        return snapshot;
    }

    boolean startClose() {
        return switch (stage) {
            case OPEN -> transitionTo(Stage.CLOSING);
            case FAILED -> transitionTo(Stage.CLOSING_FAILED);
            case CLOSING, CLOSING_FAILED, CLOSED, CLOSED_FAILED -> false;
        };
    }

    void completeClose() {
        stage = switch (stage) {
            case CLOSING -> Stage.CLOSED;
            case CLOSING_FAILED -> Stage.CLOSED_FAILED;
            case OPEN, FAILED, CLOSED, CLOSED_FAILED -> stage;
        };
        publish();
    }

    private void publish() {
        HealthStatus status = switch (stage) {
            case OPEN -> HealthStatus.HEALTHY;
            case CLOSING -> HealthStatus.STOPPING;
            case FAILED, CLOSING_FAILED, CLOSED_FAILED -> HealthStatus.FAILED;
            case CLOSED -> HealthStatus.STOPPED;
        };
        snapshot = new Snapshot(status, failure == null ? null : failure.getClass().getName());
    }

    private boolean transitionTo(Stage next) {
        stage = next;
        publish();
        return true;
    }

    record Snapshot(HealthStatus status, String failureType) {
    }

    private enum Stage {
        OPEN,
        FAILED,
        CLOSING,
        CLOSING_FAILED,
        CLOSED,
        CLOSED_FAILED
    }
}
