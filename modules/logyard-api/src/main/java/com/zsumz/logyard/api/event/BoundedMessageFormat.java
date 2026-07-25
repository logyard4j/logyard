package com.zsumz.logyard.api.event;

import com.zsumz.logyard.api.annotation.InternalApi;
import com.zsumz.logyard.api.failure.FailureIsolation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.Locale;

/**
 * Bounded, failure-isolated rendering for logging adapters that receive JDK-style format strings.
 *
 * <p>This boundary captures caller-owned parameters before invoking a formatter. In particular,
 * arbitrary objects cannot run their {@code toString()} implementation inside {@link MessageFormat},
 * and arbitrary-precision numbers whose expanded decimal form would exceed the capture budget are
 * represented by their bounded scientific form.</p>
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
            MessageFormatWorkBudget.Analysis analysis =
                    MessageFormatWorkBudget.analyze(template, parameters.length, capturedLength);
            if (analysis.nestedChoice()) {
                return workLimited(template);
            }
            boolean parametersTruncated = capture(parameters, captured, analysis::referenced)
                    || analysis.referencedParameterOmitted();
            if (!analysis.permits(template, captured)) {
                return workLimited(template);
            }
            String rendered = MessageFormat.format(template, captured);
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
            Object[] captured = new Object[capturedLength];
            boolean parametersTruncated = capture(parameters, captured, analysis::referenced)
                    || analysis.referencedParameterOmitted();
            BoundedFormatBuffer output = new BoundedFormatBuffer(CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
            try (Formatter formatter = new Formatter(output, Locale.getDefault(Locale.Category.FORMAT))) {
                formatter.format(analysis.pattern(), captured);
            } catch (BoundedFormatBuffer.LimitReached exhausted) {
                return new Result(template, output.value(), false, true);
            }
            return new Result(
                    template,
                    output.value(),
                    false,
                    template != pattern || !analysis.pattern().equals(template) || parametersTruncated);
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

    private static boolean capture(Object[] parameters, Object[] captured, ReferencedArgument referenced) {
        boolean truncated = false;
        for (int index = 0; index < captured.length; index++) {
            if (referenced.at(index)) {
                truncated |= capture(parameters[index], captured, index);
            }
        }
        return truncated;
    }

    private static boolean capture(Object value, Object[] captured, int index) {
        if (value == null || value instanceof Boolean || value instanceof Character || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float
                || value instanceof Double) {
            captured[index] = value;
            return false;
        }
        if (value instanceof String string) {
            String bounded = CaptureLimits.truncate(string, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
            captured[index] = bounded;
            return bounded != string;
        }
        if (value instanceof BigInteger integer) {
            Object bounded = SafeNumberCapture.bigInteger(integer);
            captured[index] = bounded instanceof BigInteger ? bounded : bounded.toString();
            return !(bounded instanceof BigInteger);
        }
        if (value instanceof BigDecimal decimal) {
            return captureDecimal(decimal, captured, index);
        }
        if (value instanceof Date date) {
            captured[index] = new Date(date.getTime());
            return false;
        }
        MessageFormatter.RenderResult rendered =
                MessageFormatter.safeRender(value, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        captured[index] = rendered.value();
        return rendered.truncated();
    }

    private static boolean captureDecimal(BigDecimal value, Object[] captured, int index) {
        if (value.precision() > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS
                || Math.abs((long) value.scale()) > CaptureLimits.MAX_CAPTURED_NUMBER_CHARS) {
            captured[index] = MessageFormatter.safeRender(value, CaptureLimits.MAX_CAPTURED_NUMBER_CHARS).value();
            return true;
        }
        Object bounded = SafeNumberCapture.bigDecimal(value);
        captured[index] = bounded instanceof BigDecimal ? bounded : bounded.toString();
        return !(bounded instanceof BigDecimal);
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
     */
    public record Result(String template, String message, boolean formatFailed, boolean truncated) {
    }
}
