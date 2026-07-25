package com.zsumz.logyard.api.event;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.IllegalFormatConversionException;
import java.util.Locale;
import java.util.TimeZone;

/** Closed date/time rendering that never consults an application-provided default {@link java.util.TimeZone}. */
final class TrustedDateTimeRenderer {
    private TrustedDateTimeRenderer() {
    }

    static String messageFormat(
            Object value,
            String type,
            String style,
            Locale locale,
            ZoneId zone) {
        DateFormat formatter = messageFormatter(type, style, locale);
        formatter.setTimeZone(TimeZone.getTimeZone(zone));
        return formatter.format(trustedDate(value, type.isEmpty() ? 't' : type.charAt(0)));
    }

    static String printf(Object value, char suffix, Locale locale, ZoneId zone) {
        ZonedDateTime temporal = temporal(value, suffix).withZoneSameInstant(zone);
        return switch (suffix) {
            case 'H' -> digits(temporal.getHour(), 2);
            case 'I' -> digits(clockHour(temporal), 2);
            case 'k' -> spaces(temporal.getHour(), 2);
            case 'l' -> spaces(clockHour(temporal), 2);
            case 'M' -> digits(temporal.getMinute(), 2);
            case 'S' -> digits(temporal.getSecond(), 2);
            case 'L' -> digits(temporal.getNano() / 1_000_000, 3);
            case 'N' -> digits(temporal.getNano(), 9);
            case 'p' -> pattern(temporal, "a", locale).toLowerCase(locale);
            case 'z' -> pattern(temporal, "xx", locale);
            case 'Z' -> pattern(temporal, "z", locale);
            case 's' -> Long.toString(temporal.toInstant().getEpochSecond());
            case 'Q' -> Long.toString(temporal.toInstant().toEpochMilli());
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
            case 'e' -> spaces(temporal.getDayOfMonth(), 2);
            case 'R' -> printf(value, 'H', locale, zone) + ':' + printf(value, 'M', locale, zone);
            case 'T' -> printf(value, 'R', locale, zone) + ':' + printf(value, 'S', locale, zone);
            case 'r' -> pattern(temporal, "hh:mm:ss a", locale);
            case 'D' -> printf(value, 'm', locale, zone) + '/' + printf(value, 'd', locale, zone)
                    + '/' + printf(value, 'y', locale, zone);
            case 'F' -> printf(value, 'Y', locale, zone) + '-' + printf(value, 'm', locale, zone)
                    + '-' + printf(value, 'd', locale, zone);
            case 'c' -> pattern(temporal, "EEE MMM dd HH:mm:ss z yyyy", locale);
            default -> throw new IllegalFormatConversionException(suffix, value == null ? Object.class : value.getClass());
        };
    }

    private static DateFormat messageFormatter(String type, String style, Locale locale) {
        int dateStyle = switch (style.toLowerCase(Locale.ROOT)) {
            case "", "medium" -> DateFormat.MEDIUM;
            case "short" -> DateFormat.SHORT;
            case "long" -> DateFormat.LONG;
            case "full" -> DateFormat.FULL;
            default -> -1;
        };
        if (dateStyle >= 0) {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "time" -> DateFormat.getTimeInstance(dateStyle, locale);
                case "datetime" -> DateFormat.getDateTimeInstance(dateStyle, dateStyle, locale);
                default -> DateFormat.getDateInstance(dateStyle, locale);
            };
        }
        return new SimpleDateFormat(style, locale);
    }

    private static Date trustedDate(Object value, char conversion) {
        if (value != null && value.getClass() == Date.class) {
            return (Date) value;
        }
        if (value instanceof Long number) {
            return new Date(number);
        }
        throw new IllegalFormatConversionException(conversion, value == null ? Object.class : value.getClass());
    }

    private static ZonedDateTime temporal(Object value, char conversion) {
        return Instant.ofEpochMilli(trustedDate(value, conversion).getTime()).atZone(ZoneId.of("UTC"));
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

    private static String spaces(int value, int width) {
        String text = Integer.toString(value);
        return text.length() < width ? " ".repeat(width - text.length()) + text : text;
    }
}
