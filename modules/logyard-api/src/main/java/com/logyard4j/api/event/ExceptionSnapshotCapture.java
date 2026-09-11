package com.logyard4j.api.event;

import com.logyard4j.api.failure.FailureIsolation;

import java.util.ArrayList;
import java.util.List;

/** Captures a bounded detached throwable graph for {@link ExceptionSnapshot}. */
final class ExceptionSnapshotCapture {
    private ExceptionSnapshotCapture() {
    }

    static ExceptionSnapshot capture(Throwable throwable, CaptureContext context) {
        return throwable == null ? null : capture(throwable, context, 0);
    }

    private static ExceptionSnapshot capture(Throwable throwable, CaptureContext context, int depth) {
        if (depth >= ExceptionSnapshot.MAX_CAUSE_DEPTH) {
            context.markTruncated();
            return marker("[maximum cause depth reached]");
        }
        CaptureContext.ReferenceState reference = context.enterException(throwable);
        if (reference == CaptureContext.ReferenceState.CYCLE) {
            context.markTruncated();
            return marker("[circular exception reference]");
        }
        if (reference == CaptureContext.ReferenceState.SHARED) {
            context.markTruncated();
            return marker("[shared exception reference]");
        }
        if (!context.claimExceptionNode()) {
            context.leaveException(throwable);
            return marker("[event exception budget exhausted]");
        }
        try {
            boolean truncated = false;
            CapturedText capturedType = context.captureExceptionTypeText(
                    throwable.getClass().getName(),
                    CaptureLimits.MAX_NAME_CHARS);
            String type = capturedType.value();
            if (type == null || type.isBlank()) {
                type = "[exception type omitted]";
                truncated = true;
                context.markTruncated();
            } else {
                truncated = capturedType.truncated();
            }
            String message;
            try {
                CapturedText capturedMessage = context.captureExceptionMessageText(
                        throwable.getMessage(),
                        ExceptionSnapshot.MAX_MESSAGE_CHARS);
                message = capturedMessage.value();
                truncated |= capturedMessage.truncated();
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                CapturedText failedMessage = context.captureExceptionMessageText(
                        "[message accessor failed: " + failure.getClass().getName() + ']',
                        ExceptionSnapshot.MAX_MESSAGE_CHARS);
                message = failedMessage.value();
                truncated = true;
            }

            StackTraceElement[] sourceFrames;
            try {
                sourceFrames = throwable.getStackTrace();
                if (sourceFrames == null) {
                    sourceFrames = new StackTraceElement[0];
                }
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceFrames = new StackTraceElement[] {
                        new StackTraceElement(
                                "logyard.exception",
                                "unavailable",
                                "stack trace accessor failed: " + failure.getClass().getName(),
                                -1)
                };
                truncated = true;
            }
            int frameCount = Math.min(sourceFrames.length, ExceptionSnapshot.MAX_FRAMES_PER_NODE);
            List<StackTraceElement> frames = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) {
                if (!context.canCaptureExceptionFrame() || !context.claimFrame()) {
                    truncated = true;
                    context.markTruncated();
                    break;
                }
                StackTraceElement frame = sourceFrames[index];
                if (frame != null) {
                    ExceptionFrameCapture.Result capturedFrame = ExceptionFrameCapture.capture(frame, context);
                    frames.add(capturedFrame.frame());
                    truncated |= capturedFrame.truncated();
                }
            }
            if (sourceFrames.length > frames.size()) {
                truncated = true;
                context.markTruncated();
            }

            Throwable sourceCause;
            try {
                sourceCause = throwable.getCause();
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceCause = null;
                truncated = true;
            }
            ExceptionSnapshot cause = null;
            if (sourceCause == throwable) {
                truncated = true;
                context.markTruncated();
            } else if (sourceCause != null) {
                cause = capture(sourceCause, context, depth + 1);
            }
            if (cause != null && cause.truncated()) {
                truncated = true;
            }

            Throwable[] sourceSuppressed;
            try {
                sourceSuppressed = throwable.getSuppressed();
                if (sourceSuppressed == null) {
                    sourceSuppressed = new Throwable[0];
                }
            } catch (Throwable failure) {
                FailureIsolation.prepareForRecovery(failure);
                sourceSuppressed = new Throwable[0];
                truncated = true;
            }
            int suppressedCount = Math.min(sourceSuppressed.length, ExceptionSnapshot.MAX_SUPPRESSED_PER_NODE);
            List<ExceptionSnapshot> suppressed = new ArrayList<>(suppressedCount);
            for (int index = 0; index < suppressedCount; index++) {
                if (!context.claimEntry()) {
                    truncated = true;
                    break;
                }
                Throwable current = sourceSuppressed[index];
                if (current != null) {
                    suppressed.add(capture(current, context, depth + 1));
                }
            }
            if (sourceSuppressed.length > suppressed.size()) {
                truncated = true;
                context.markTruncated();
            }

            for (ExceptionSnapshot current : suppressed) {
                if (current.truncated()) {
                    truncated = true;
                    break;
                }
            }

            return new ExceptionSnapshot(type, message, frames, suppressed, cause, truncated);
        } finally {
            context.leaveException(throwable);
        }
    }

    private static ExceptionSnapshot marker(String message) {
        return new ExceptionSnapshot(
                "com.logyard4j.api.event.ExceptionSnapshot",
                message,
                List.of(),
                List.of(),
                null,
                true);
    }
}
