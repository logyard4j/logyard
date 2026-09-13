package com.logyard4j.logyard.api.event;

import com.logyard4j.logyard.api.annotation.InternalApi;
import com.logyard4j.logyard.api.failure.FailureIsolation;

import java.text.MessageFormat;
import java.util.Formatter;
import java.util.IllegalFormatException;

/**
 * Bounded, failure-isolated rendering for logging adapters that receive JDK-style format strings.
 *
 * <p>This boundary captures caller-owned parameters before invoking a formatter. In particular,
 * arbitrary objects cannot run their {@code toString()} implementation inside {@link MessageFormat},
 * and arbitrary-precision numbers whose expanded decimal form would exceed the capture budget are
 * represented by their bounded scientific form. Legacy {@link java.util.Date} and epoch-millisecond
 * {@link Long} temporal conversions use a deterministic UTC, proleptic-Gregorian policy; no adapter
 * formatting path consults the process-default {@link java.util.TimeZone}. Date/time elements accept
 * only the localized {@code short}, {@code medium}, {@code long}, and {@code full} styles; custom
 * temporal patterns take the bounded format-failure path.</p>
 *
 * @hidden
 */
@InternalApi
public final class BoundedMessageFormat {
    private static final int MAX_PRINTF_FIELD_WIDTH = CaptureLimits.MAX_CAPTURED_NUMBER_CHARS;

    private BoundedMessageFormat() {
    }

    /**
     * Renders a {@link MessageFormat} pattern with bounded inputs and output.
     *
     * @param pattern localized message pattern, or {@code null}
     * @param parameters caller-owned parameters, or {@code null}
     * @return bounded render result
     */
    public static Result messageFormat(String pattern, Object[] parameters) {
        String template = boundedTemplate(pattern);
        if (template == null || parameters == null || parameters.length == 0) {
            return literal(template, template != pattern);
        }
        try {
            int capturedLength = Math.min(parameters.length, CaptureLimits.MAX_ARGUMENTS);
            Object[] captured = new Object[capturedLength];
            boolean[] capturedArguments = new boolean[capturedLength];
            MessageFormatWorkBudget.Analysis analysis =
                    MessageFormatWorkBudget.analyze(template, parameters.length, capturedLength);
            boolean parametersTruncated =
                    capture(parameters, captured, capturedArguments, analysis::referenced);
            if (!analysis.resolveChoices(captured)) {
                return workLimited(template);
            }
            parametersTruncated |= capture(parameters, captured, capturedArguments, analysis::referenced)
                    || analysis.referencedParameterOmitted();
            if (!analysis.permits(captured)) {
                return workLimited(template);
            }
            String rendered = analysis.render(captured);
            return success(template, rendered, template != pattern || parametersTruncated);
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            return failed(template, template != pattern);
        }
    }

    /**
     * Renders a {@link java.util.Formatter} pattern with bounded inputs and field widths.
     *
     * @param pattern printf-style pattern, or {@code null}
     * @param parameters caller-owned parameters, or {@code null}
     * @return bounded render result
     */
    public static Result printf(String pattern, Object[] parameters) {
        String template = boundedTemplate(pattern);
        if (template == null || parameters == null || parameters.length == 0) {
            return literal(template, template != pattern);
        }
        try {
            int capturedLength = Math.min(parameters.length, CaptureLimits.MAX_ARGUMENTS);
            BoundedPrintfPattern.Analysis analysis =
                    BoundedPrintfPattern.analyze(template, MAX_PRINTF_FIELD_WIDTH, parameters.length, capturedLength);
            PrintfArgumentCapture.CapturedArguments captured = analysis.capture(parameters);
            boolean parametersTruncated = captured.truncated() || analysis.referencedParameterOmitted();
            BoundedFormatBuffer output = new BoundedFormatBuffer(CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            try (Formatter formatter = new Formatter(output, analysis.locale())) {
                formatter.format(analysis.pattern(), captured.values());
            } catch (BoundedFormatBuffer.LimitReached exhausted) {
                return new Result(template, output.value(), false, true);
            }
            return new Result(
                    template,
                    output.value(),
                    false,
                    template != pattern || analysis.syntaxBounded() || parametersTruncated);
        } catch (IllegalFormatException failure) {
            return failed(template, template != pattern);
        } catch (Throwable failure) {
            FailureIsolation.prepareForRecovery(failure);
            return failed(template, template != pattern);
        }
    }

    /**
     * Bounds a message that must not be interpreted as a format string.
     *
     * @param message literal message, or {@code null}
     * @return bounded literal result
     */
    public static Result literal(String message) {
        String bounded = boundedMessage(message);
        return literal(bounded, bounded != message);
    }

    private static boolean capture(
            Object[] parameters,
            Object[] captured,
            boolean[] capturedArguments,
            ReferencedArgument referenced) {
        boolean truncated = false;
        for (int index = 0; index < captured.length; index++) {
            if (referenced.at(index) && !capturedArguments[index]) {
                truncated |= capture(parameters[index], captured, index);
                capturedArguments[index] = true;
            }
        }
        return truncated;
    }

    private static boolean capture(Object value, Object[] captured, int index) {
        FormatArgumentCapture.Captured argument = FormatArgumentCapture.capture(value);
        captured[index] = argument.value();
        return argument.truncated();
    }

    private static Result success(String template, String rendered, boolean inputTruncated) {
        String message = boundedMessage(rendered);
        return new Result(template, message, false, inputTruncated || message != rendered);
    }

    private static Result failed(String template, boolean truncated) {
        return new Result(template, template, true, truncated);
    }

    private static Result workLimited(String template) {
        String marker = "[format expansion omitted] ";
        return new Result(template, boundedMessage(marker + template), false, true);
    }

    private static Result literal(String message, boolean truncated) {
        return new Result(message, message, false, truncated);
    }

    private static String boundedTemplate(String pattern) {
        return CaptureLimits.truncate(pattern, CaptureLimits.MAX_EVENT_TEMPLATE_CHARS);
    }

    private static String boundedMessage(String message) {
        return CaptureLimits.truncate(message, CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
    }

    @FunctionalInterface
    private interface ReferencedArgument {
        boolean at(int index);
    }

    /**
     * One bounded adapter render.
     *
     * @param template bounded localized template
     * @param message bounded rendered message
     * @param formatFailed whether the formatter rejected the captured input
     * @param truncated whether any template, field width, parameter, or output was truncated
     * @hidden
     */
    @InternalApi
    public record Result(String template, String message, boolean formatFailed, boolean truncated) {
    }
}
