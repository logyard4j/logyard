package com.logyard4j.logyard.api.event;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.IllegalFormatConversionException;
import java.util.Locale;

/** Closed UTC/proleptic-Gregorian renderer over immutable epoch-millisecond values. */
final class TrustedDateTimeRenderer {
    private static final int MAX_LOCALIZED_STYLE_CHARS = 16;
    private static final DateTimeFormatter DATE_DISPLAY =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss 'UTC' uuuu", Locale.ROOT)
                    .withZone(TrustedFormattingZone.defaultZone());

    private TrustedDateTimeRenderer() {
    }

    static String display(CapturedTemporal value) {
        return DATE_DISPLAY.format(value.at(TrustedFormattingZone.defaultZone()));
    }

    static String messageFormat(Object value, String type, String style, Locale locale, ZoneId zone) {
        return messageFormatter(type, style, locale)
                .withZone(zone)
                .format(temporal(value, type.isEmpty() ? 't' : type.charAt(0), zone));
    }

    static void requireSupportedMessageStyle(String style) {
        localizedStyle(style);
    }

    static String printf(Object value, char suffix, Locale locale, ZoneId zone) {
        long epochMillis = epochMillis(value, suffix);
        return printf(epochMillis, temporal(epochMillis, zone), suffix, locale);
    }

    private static String printf(long epochMillis, ZonedDateTime temporal, char suffix, Locale locale) {
        return switch (suffix) {
            case 'H' -> digits(temporal.getHour(), 2);
            case 'I' -> digits(clockHour(temporal), 2);
            case 'k' -> Integer.toString(temporal.getHour());
            case 'l' -> Integer.toString(clockHour(temporal));
            case 'M' -> digits(temporal.getMinute(), 2);
            case 'S' -> digits(temporal.getSecond(), 2);
            case 'L' -> digits(temporal.getNano() / 1_000_000, 3);
            case 'N' -> digits(temporal.getNano(), 9);
            case 'p' -> pattern(temporal, "a", locale).toLowerCase(locale);
            case 'z' -> pattern(temporal, "xx", locale);
            case 'Z' -> pattern(temporal, "z", locale);
            case 's' -> Long.toString(epochMillis / 1_000L);
            case 'Q' -> Long.toString(epochMillis);
            case 'B' -> pattern(temporal, "MMMM", locale);
            case 'b', 'h' -> pattern(temporal, "MMM", locale);
            case 'A' -> pattern(temporal, "EEEE", locale);
            case 'a' -> pattern(temporal, "EEE", locale);
            case 'C' -> digits(Math.floorDiv(temporal.getYear(), 100), 2);
            case 'Y' -> digits(temporal.getYear(), 4);
            case 'y' -> digits(Math.floorMod(temporal.getYear(), 100), 2);
            case 'j' -> digits(temporal.getDayOfYear(), 3);
            case 'm' -> digits(temporal.getMonthValue(), 2);
            case 'd' -> digits(temporal.getDayOfMonth(), 2);
            case 'e' -> Integer.toString(temporal.getDayOfMonth());
            case 'R' -> printf(epochMillis, temporal, 'H', locale) + ':' + printf(epochMillis, temporal, 'M', locale);
            case 'T' -> printf(epochMillis, temporal, 'R', locale) + ':' + printf(epochMillis, temporal, 'S', locale);
            case 'r' -> pattern(temporal, "hh:mm:ss a", locale);
            case 'D' -> printf(epochMillis, temporal, 'm', locale) + '/' + printf(epochMillis, temporal, 'd', locale)
                    + '/' + printf(epochMillis, temporal, 'y', locale);
            case 'F' -> printf(epochMillis, temporal, 'Y', locale) + '-' + printf(epochMillis, temporal, 'm', locale)
                    + '-' + printf(epochMillis, temporal, 'd', locale);
            case 'c' -> pattern(temporal, "EEE MMM dd HH:mm:ss z yyyy", locale);
            default -> throw new IllegalFormatConversionException(suffix, Object.class);
        };
    }

    private static DateTimeFormatter messageFormatter(String type, String style, Locale locale) {
        FormatStyle localizedStyle = localizedStyle(style);
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "time" -> DateTimeFormatter.ofLocalizedTime(localizedStyle).withLocale(locale);
            case "datetime" -> DateTimeFormatter.ofLocalizedDateTime(localizedStyle).withLocale(locale);
            default -> DateTimeFormatter.ofLocalizedDate(localizedStyle).withLocale(locale);
        };
    }

    private static FormatStyle localizedStyle(String style) {
        if (style.length() > MAX_LOCALIZED_STYLE_CHARS) {
            throw unsupportedMessageStyle();
        }
        return switch (style.trim().toLowerCase(Locale.ROOT)) {
            case "", "medium" -> FormatStyle.MEDIUM;
            case "short" -> FormatStyle.SHORT;
            case "long" -> FormatStyle.LONG;
            case "full" -> FormatStyle.FULL;
            default -> throw unsupportedMessageStyle();
        };
    }

    private static IllegalArgumentException unsupportedMessageStyle() {
        return new IllegalArgumentException("custom date/time styles are not supported by bounded adapter formatting");
    }

    private static long epochMillis(Object value, char conversion) {
        if (value instanceof CapturedTemporal temporal) {
            return temporal.epochMillis();
        }
        if (value instanceof Long number) {
            return number;
        }
        throw new IllegalFormatConversionException(conversion, value == null ? Object.class : value.getClass());
    }

    private static ZonedDateTime temporal(Object value, char conversion, ZoneId zone) {
        return temporal(epochMillis(value, conversion), zone);
    }

    private static ZonedDateTime temporal(long epochMillis, ZoneId zone) {
        return new CapturedTemporal(epochMillis).at(zone);
    }

    private static String pattern(ZonedDateTime temporal, String pattern, Locale locale) {
        return DateTimeFormatter.ofPattern(pattern, locale).format(temporal);
    }

    private static int clockHour(ZonedDateTime temporal) {
        int hour = temporal.getHour() % 12;
        return hour == 0 ? 12 : hour;
    }

    private static String digits(int value, int width) {
        String text = Integer.toString(value);
        return value >= 0 && text.length() < width ? "0".repeat(width - text.length()) + text : text;
    }
}
