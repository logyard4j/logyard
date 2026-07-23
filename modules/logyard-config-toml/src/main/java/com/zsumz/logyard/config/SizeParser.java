package com.zsumz.logyard.config;

import java.math.BigDecimal;
import java.util.Locale;

final class SizeParser {
    private SizeParser() {
    }

    static long parse(String value) {
        String normalized = value.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        int split = 0;
        while (split < normalized.length() && (Character.isDigit(normalized.charAt(split))
                || normalized.charAt(split) == '.')) {
            split++;
        }
        if (split == 0) {
            throw new IllegalArgumentException("size must look like 256KiB, 10MiB, or 1GiB");
        }
        BigDecimal amount = new BigDecimal(normalized.substring(0, split));
        long multiplier = switch (normalized.substring(split)) {
            case "", "b" -> 1L;
            case "kb" -> 1_000L;
            case "mb" -> 1_000_000L;
            case "gb" -> 1_000_000_000L;
            case "kib" -> 1L << 10;
            case "mib" -> 1L << 20;
            case "gib" -> 1L << 30;
            default -> throw new IllegalArgumentException("unknown size unit in '" + value + "'");
        };
        long bytes = amount.multiply(BigDecimal.valueOf(multiplier)).longValueExact();
        if (bytes < 1) {
            throw new IllegalArgumentException("size must be at least one byte");
        }
        return bytes;
    }
}
