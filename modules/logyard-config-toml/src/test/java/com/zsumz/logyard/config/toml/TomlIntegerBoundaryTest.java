package com.zsumz.logyard.config.toml;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TomlIntegerBoundaryTest {
    private static final BigInteger FIRST_INVALID_SIGNED_INTEGER = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

    @Test
    void acceptsSignedLongMaximumInEveryNonDecimalBase() {
        for (Base base : bases()) {
            assertEquals(Long.MAX_VALUE, parse(base.token(base.maximumDigits())));
            assertEquals(Long.MAX_VALUE, parse(base.token(group(base.maximumDigits()))));
            assertEquals(Long.MAX_VALUE, parse(base.token("0" + base.maximumDigits())));
        }
    }

    @Test
    void rejectsFirstUnsignedMagnitudeOutsideTheSignedLongRange() {
        for (Base base : bases()) {
            assertOutsideRange(base.token(FIRST_INVALID_SIGNED_INTEGER.toString(base.radix())));
        }
    }

    @Test
    void rejectsExcessivelyLongNonDecimalIntegers() {
        for (Base base : bases()) {
            assertOutsideRange(base.token(String.valueOf(base.highDigit()).repeat(256)));
            assertOutsideRange(base.token(group(String.valueOf(base.highDigit()).repeat(256))));
        }
    }

    @Test
    void rejectsMalformedNonDecimalSeparatorsAndDigits() {
        for (Base base : bases()) {
            assertInvalidInteger(base.token("_1"));
            assertInvalidInteger(base.token("1_"));
            assertInvalidInteger(base.token("1__0"));
            assertInvalidInteger(base.token(base.invalidDigit()));
        }
    }

    private static Object parse(String token) {
        return TomlParser.parse("integer.toml", "value = " + token + '\n').root().get("value");
    }

    private static void assertOutsideRange(String token) {
        TomlParseException failure = assertThrows(TomlParseException.class, () -> parse(token));
        assertTrue(failure.getMessage().contains("outside the supported range"));
    }

    private static void assertInvalidInteger(String token) {
        TomlParseException failure = assertThrows(TomlParseException.class, () -> parse(token));
        assertTrue(failure.getMessage().contains("invalid integer syntax"));
    }

    private static String group(String digits) {
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 4);
        int firstGroupLength = digits.length() % 4;
        int cursor = 0;
        if (firstGroupLength > 0) {
            grouped.append(digits, 0, firstGroupLength);
            cursor = firstGroupLength;
        }
        while (cursor < digits.length()) {
            if (!grouped.isEmpty()) {
                grouped.append('_');
            }
            grouped.append(digits, cursor, cursor + 4);
            cursor += 4;
        }
        return grouped.toString();
    }

    private static List<Base> bases() {
        return List.of(
                new Base("0x", 16, 'f', "g"),
                new Base("0o", 8, '7', "8"),
                new Base("0b", 2, '1', "2"));
    }

    private record Base(String prefix, int radix, char highDigit, String invalidDigit) {
        String maximumDigits() {
            return Long.toString(Long.MAX_VALUE, radix);
        }

        String token(String digits) {
            return prefix + digits;
        }
    }
}
