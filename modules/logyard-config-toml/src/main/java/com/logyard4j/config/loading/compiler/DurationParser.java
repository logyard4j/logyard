package com.logyard4j.config.loading.compiler;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

final class DurationParser {
    private DurationParser() {
    }

    static Duration parse(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("duration must not be empty");
        }
        int split = 0;
        while (split < normalized.length()) {
            char c = normalized.charAt(split);
            if (Character.isDigit(c) || c == '.' || (split == 0 && c == '+')) {
                split++;
            } else {
                break;
            }
        }
        if (split == 0 || split == normalized.length()) {
            throw new IllegalArgumentException("duration must look like 250ms, 3s, 2m, 1h, or 1d");
        }
        BigDecimal amount = new BigDecimal(normalized.substring(0, split));
        BigDecimal nanosPerUnit = switch (normalized.substring(split)) {
            case "ns" -> BigDecimal.ONE;
            case "us", "µs" -> BigDecimal.valueOf(1_000L);
            case "ms" -> BigDecimal.valueOf(1_000_000L);
            case "s" -> BigDecimal.valueOf(1_000_000_000L);
            case "m" -> BigDecimal.valueOf(60_000_000_000L);
            case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
            case "d" -> BigDecimal.valueOf(86_400_000_000_000L);
            default -> throw new IllegalArgumentException("unknown duration unit in '" + value + "'");
        };
        long nanos = amount.multiply(nanosPerUnit).longValueExact();
        if (nanos < 0) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return Duration.ofNanos(nanos);
    }
}
