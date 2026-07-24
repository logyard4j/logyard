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
            String capturedLoggerName = context.captureIdentityText(
                    Objects.requireNonNull(loggerName, "loggerName"),
                    CaptureLimits.MAX_NAME_CHARS);
            String capturedEventName = context.captureIdentityText(eventName, CaptureLimits.MAX_NAME_CHARS);
            String capturedTemplate = context.captureTemplateText(messageTemplate, CaptureLimits.MAX_EVENT_TEMPLATE_CHARS);
            String capturedThreadName = context.captureIdentityText(
                    Objects.requireNonNullElse(threadName, "unknown"),
                    CaptureLimits.MAX_NAME_CHARS);

            ExceptionSnapshot capturedException = ExceptionSnapshot.capture(throwable, context);
            Object[] capturedArguments = ValueCapture.arguments(argumentSupplier.get(), context);
            CaptureAllowance attributeAllowance = context.payloadAllowance();
            AttributeSet suppliedAttributes = Objects.requireNonNullElse(attributeSupplier.get(), AttributeSet.EMPTY);
            AttributeSet capturedAttributes = attributesCapturedInContext ? suppliedAttributes : suppliedAttributes.recapture(context);

            int omittedArguments = Math.max(0, suppliedArgumentCount - capturedArguments.length);
            if (omittedArguments > 0) {
                context.markTruncated();
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
                    CaptureLimits.MAX_RENDERED_MESSAGE_CHARS,
                    context.remainingEntries(),
                    attributeAllowance,
                    context.truncated());
        });
    }
}
