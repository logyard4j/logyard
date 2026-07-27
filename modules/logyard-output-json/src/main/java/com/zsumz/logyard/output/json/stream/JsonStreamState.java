package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.diagnostics.HealthStatus;

/** Sticky lifecycle and first-failure record for one JSON stream transport. */
final class JsonStreamState {
    private Stage stage = Stage.OPEN;
    private Throwable failure;

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
    }

    boolean failed() {
        return failure != null;
    }

    Throwable failure() {
        return failure;
    }

    String failureType() {
        return failure == null ? null : failure.getClass().getName();
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
    }

    HealthStatus healthStatus() {
        return switch (stage) {
            case OPEN, CLOSING -> HealthStatus.HEALTHY;
            case FAILED, CLOSING_FAILED, CLOSED_FAILED -> HealthStatus.FAILED;
            case CLOSED -> HealthStatus.STOPPED;
        };
    }

    private boolean transitionTo(Stage next) {
        stage = next;
        return true;
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
