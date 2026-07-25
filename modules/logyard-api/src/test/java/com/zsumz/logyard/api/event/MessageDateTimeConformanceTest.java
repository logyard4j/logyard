package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MessageDateTimeConformanceTest {
    @Test
    void localizedDateAndTimeStylesMatchMessageFormatUnderTheUtcPolicy() {
        List<Date> values = List.of(
                new Date(0L),
                new Date(Instant.parse("2000-02-29T12:34:56.789Z").toEpochMilli()),
                new Date(Instant.parse("2024-11-03T07:01:00Z").toEpochMilli()));
        for (Locale locale : List.of(Locale.US, Locale.FRANCE, Locale.JAPAN)) {
            withDefaults(locale, () -> {
                for (Date value : values) {
                    assertPattern("{0}", value, locale);
                    for (String type : List.of("date", "time")) {
                        for (String style : List.of("short", "medium", "long", "full")) {
                            assertPattern("{0," + type + ',' + style + '}', value, locale);
                        }
                    }
                }
            });
        }
    }

    private static void assertPattern(String pattern, Date value, Locale locale) {
        String expected = new MessageFormat(pattern, locale).format(new Object[] {value});
        BoundedMessageFormat.Result actual = BoundedMessageFormat.messageFormat(pattern, new Object[] {value});

        assertFalse(actual.formatFailed(), pattern + " / " + locale);
        assertEquals(expected, actual.message(), pattern + " / " + locale);
    }

    private static void withDefaults(Locale locale, Runnable action) {
        TimeZone originalZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")));
            Locale.setDefault(Locale.Category.FORMAT, locale);
            action.run();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }
}
