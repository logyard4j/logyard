package com.logyard4j.api.event;

import com.logyard4j.api.Level;

import java.util.Objects;
import java.util.function.Supplier;

/** Establishes one event-wide boundary; optional suppliers run inside it, and direct values need no wrappers. */
final class LogEventCapture {
    private LogEventCapture() {
    }

    static LogEventState capture(
            long timestampMillis,
            long observedTimestampUnixNanos,
            Level level,
            String loggerName,
            String eventName,
            String messageTemplate,
            Object[] arguments,
            AttributeSet attributes,
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
            Object[] capturedArguments = ValueCapture.arguments(
                    argumentSupplier == null ? arguments : argumentSupplier.get(), context);
            CaptureAllowance attributeAllowance = context.payloadAllowance();
            AttributeSet suppliedAttributes = Objects.requireNonNullElse(
                    attributeSupplier == null ? attributes : attributeSupplier.get(), AttributeSet.EMPTY);
            AttributeSet capturedAttributes = attributesCapturedInContext ? suppliedAttributes : suppliedAttributes.recapture(context);
            if (capturedAttributes.captureTruncated()) {
                context.markTruncated();
            }

            int omittedArguments = Math.max(0, suppliedArgumentCount - capturedArguments.length);
            if (omittedArguments > 0) {
                context.markTruncated();
                capturedAttributes = capturedAttributes.withSystemAttribute(SystemAttributes.ARGUMENTS_OMITTED, omittedArguments);
            }
            if (context.truncated()) {
                capturedAttributes = capturedAttributes.withSystemAttribute(SystemAttributes.CAPTURE_TRUNCATED, true);
            }
            return new LogEventState(
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
                    context.remainingEntries(),
                    attributeAllowance,
                    context.truncated(),
                    LazyRenderedMessage.forEvent(capturedTemplate, capturedArguments));
        });
    }
}
