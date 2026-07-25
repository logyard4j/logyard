package com.zsumz.logyard.api.event;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ChoiceFormat;
import java.text.Format;
import java.util.Date;

/** Conservative construction bounds for one JDK formatter element. */
final class FormattedValueWorkBudget {
    private static final long FLOATING_POINT_DECIMAL_SPAN = 1_100L;
    private static final long FORMATTER_OVERHEAD = 64L;

    private FormattedValueWorkBudget() {
    }

    static long maximum(Object value, Format format, int sourceCharacters) {
        if (value == null) {
            return 4L;
        }
        if (format instanceof ChoiceFormat) {
            throw new IllegalArgumentException("ChoiceFormat requires a selected branch plan");
        }
        if (value instanceof BigDecimal decimal) {
            return decimalMaximum(decimal, sourceCharacters);
        }
        if (value instanceof BigInteger integer) {
            return integerMaximum(decimalDigits(integer), sourceCharacters);
        }
        if (value instanceof Double || value instanceof Float) {
            return numericMaximum(FLOATING_POINT_DECIMAL_SPAN, FLOATING_POINT_DECIMAL_SPAN, sourceCharacters);
        }
        if (value instanceof Number number) {
            return integerMaximum(decimalCharacters(number.longValue()), sourceCharacters);
        }
        if (value instanceof Date) {
            return saturatedAdd(saturatedMultiply(sourceCharacters, 2L), 256L);
        }
        if (value instanceof CharSequence sequence) {
            return sequence.length();
        }
        if (value instanceof Character) {
            return 1L;
        }
        if (value instanceof Boolean) {
            return 5L;
        }
        return CaptureLimits.MAX_CAPTURED_NUMBER_CHARS;
    }

    private static long decimalMaximum(BigDecimal value, int sourceCharacters) {
        long precision = Math.max(1L, value.precision());
        long scale = value.scale();
        long integerDigits = Math.max(1L, precision - scale);
        long fractionDigits = Math.max(0L, scale);
        return numericMaximum(integerDigits, fractionDigits, sourceCharacters);
    }

    private static long integerMaximum(long digits, int sourceCharacters) {
        return numericMaximum(Math.max(1L, digits), 0L, sourceCharacters);
    }

    private static long numericMaximum(long integerDigits, long fractionDigits, int sourceCharacters) {
        long groupedInteger = saturatedMultiply(integerDigits, 2L);
        long formatterSyntax = saturatedMultiply(sourceCharacters, 2L);
        return saturatedAdd(
                saturatedAdd(groupedInteger, fractionDigits),
                saturatedAdd(formatterSyntax, FORMATTER_OVERHEAD));
    }

    private static long decimalDigits(BigInteger value) {
        int bits = value.abs().bitLength();
        return bits == 0 ? 1L : ((long) bits * 1_234L >>> 12) + 1L;
    }

    private static int decimalCharacters(long value) {
        if (value == Long.MIN_VALUE) {
            return 19;
        }
        long magnitude = Math.abs(value);
        int digits = 1;
        while (magnitude >= 10L) {
            magnitude /= 10L;
            digits++;
        }
        return digits;
    }

    private static long saturatedMultiply(long value, long multiplier) {
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
