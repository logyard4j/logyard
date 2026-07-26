package com.zsumz.logyard.output.json.stream;

import com.zsumz.logyard.api.diagnostics.HealthStatus;

/** Sticky lifecycle and first-failure record for one JSON stream transport. */
final class JsonStreamState {
    private Stage stage = Stage.OPEN;
    private Throwable failure;
    private boolean closeStarted;

    void requireOpen() {
        if (stage == Stage.FAILED) {
            throw new IllegalStateException("Logyard JSON stream output failed", failure);
        }
        if (stage == Stage.CLOSED) {
            throw new IllegalStateException("Logyard JSON stream output is closed");
        }
    }

    void failed(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        stage = Stage.FAILED;
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
        if (closeStarted) {
            return false;
        }
        closeStarted = true;
        if (stage == Stage.OPEN) {
            stage = Stage.CLOSED;
        }
        return true;
    }

    HealthStatus healthStatus() {
        return switch (stage) {
            case OPEN -> HealthStatus.HEALTHY;
            case FAILED -> HealthStatus.FAILED;
            case CLOSED -> HealthStatus.STOPPED;
        };
    }

    private enum Stage {
        OPEN,
        FAILED,
        CLOSED
    }
}
