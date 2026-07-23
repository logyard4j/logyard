package com.zsumz.logyard.config.toml;

import java.math.BigInteger;

/** Strict parser for TOML booleans and numeric bare values. */
final class TomlBareValueParser {
    private TomlBareValueParser() {
    }

    static Object parse(String token, TomlCursor cursor) {
        if (token.equals("true")) {
            return Boolean.TRUE;
        }
        if (token.equals("false")) {
            return Boolean.FALSE;
        }
        try {
            if (token.matches("0x[0-9A-Fa-f](?:_?[0-9A-Fa-f])*")) {
                return parseNonDecimalInteger(token, 16);
            }
            if (token.matches("0o[0-7](?:_?[0-7])*")) {
                return parseNonDecimalInteger(token, 8);
            }
            if (token.matches("0b[01](?:_?[01])*")) {
                return parseNonDecimalInteger(token, 2);
            }
            if (token.matches("[+-]?(?:0|[1-9](?:_?[0-9])*)")) {
                return Long.valueOf(token.replace("_", ""));
            }
            if (token.matches("[+-]?(?:"
                    + "(?:0|[1-9](?:_?[0-9])*)\\.[0-9](?:_?[0-9])*"
                    + "|(?:0|[1-9](?:_?[0-9])*)(?:[eE][+-]?[0-9](?:_?[0-9])*)"
                    + "|(?:0|[1-9](?:_?[0-9])*)\\.[0-9](?:_?[0-9])*(?:[eE][+-]?[0-9](?:_?[0-9])*)"
                    + ")")) {
                return Double.valueOf(token.replace("_", ""));
            }
            if (token.matches("[+-]?(?:inf|nan)")) {
                if (token.endsWith("inf")) {
                    return token.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                }
                return Double.NaN;
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            cursor.fail("numeric value is outside the supported range: '" + token + "'");
        }
        if (looksLikeNumber(token)) {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                cursor.fail("invalid numeric syntax: '" + token + "'");
            }
            cursor.fail("invalid integer syntax: '" + token + "'");
        }
        cursor.fail("unsupported bare value '" + token + "'; strings must be quoted");
        throw new AssertionError("unreachable");
    }

    private static long parseNonDecimalInteger(String token, int radix) {
        return new BigInteger(token.substring(2).replace("_", ""), radix).longValueExact();
    }

    private static boolean looksLikeNumber(String token) {
        if (token.isEmpty()) {
            return false;
        }
        int first = token.charAt(0) == '+' || token.charAt(0) == '-' ? 1 : 0;
        return first < token.length() && Character.isDigit(token.charAt(first));
    }
}
