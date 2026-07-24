package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.Level;

import java.util.Objects;
import java.util.function.Supplier;

/** Establishes and applies one event-wide capture boundary. */
final class LogEventCapture {
    private LogEventCapture() {
    }

    static CapturedLogEvent capture(
            long timestampMillis,
            long observedTimestampUnixNanos,
            Level level,
            String loggerName,
            String eventName,
            String messageTemplate,
            Supplier<Object[]> argumentSupplier,
            Supplier<AttributeSet> attributeSupplier,
            int suppliedArgumentCount,
            boolean attributesCapturedInContext,
            Throwable throwable,
            long threadId,
            String threadName) {
        CaptureContext context = CaptureContext.create();
        return CaptureContext.within(context, () -> {
            Level capturedLevel = Objects.requireNonNull(level, "level");
            String capturedLoggerName = context.captureText(Objects.requireNonNull(loggerName, "loggerName"), CaptureLimits.MAX_NAME_CHARS);
            String capturedEventName = context.captureText(eventName, CaptureLimits.MAX_NAME_CHARS);
            String capturedTemplate = context.captureText(messageTemplate, CaptureLimits.MAX_TEXT_CHARS);
            String capturedThreadName = context.captureText(Objects.requireNonNullElse(threadName, "unknown"), CaptureLimits.MAX_NAME_CHARS);

            Object[] capturedArguments = ValueCapture.arguments(argumentSupplier.get(), context);
            AttributeSet suppliedAttributes = Objects.requireNonNullElse(attributeSupplier.get(), AttributeSet.EMPTY);
            AttributeSet capturedAttributes = attributesCapturedInContext ? suppliedAttributes : suppliedAttributes.recapture(context);
            ExceptionSnapshot capturedException = ExceptionSnapshot.capture(throwable, context);

            int omittedArguments = Math.max(0, suppliedArgumentCount - capturedArguments.length);
            if (omittedArguments > 0) {
                capturedAttributes = capturedAttributes.withSystemAttribute("logyard.arguments.omitted", omittedArguments);
            }
            if (context.truncated()) {
                capturedAttributes = capturedAttributes.withSystemAttribute("logyard.capture.truncated", true);
            }
            return new CapturedLogEvent(
                    timestampMillis,
                    observedTimestampUnixNanos,
                    capturedLevel,
                    capturedLoggerName,
                    capturedEventName,
                    capturedTemplate,
                    capturedArguments,
                    capturedAttributes,
                    capturedException,
                    threadId,
                    capturedThreadName,
                    context.reserveRenderedMessage(),
                    context.remainingEntries());
        });
    }
}
