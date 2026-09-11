package com.logyard4j.api.event;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.DuplicateFormatFlagsException;
import java.util.IllegalFormatPrecisionException;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TemporalPrintfConformanceTest {
    private static final String SUFFIXES = "HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc";
    private static final List<Long> MODERN_INSTANTS = List.of(
            -1L,
            0L,
            Instant.parse("2000-02-29T12:34:56.789Z").toEpochMilli(),
            Instant.parse("2024-03-10T07:59:00Z").toEpochMilli(),
            Instant.parse("2024-03-10T08:01:00Z").toEpochMilli(),
            Instant.parse("2024-11-03T06:59:00Z").toEpochMilli(),
            Instant.parse("2024-11-03T07:01:00Z").toEpochMilli());

    @Test
    void everyTemporalSuffixMatchesFormatterForDateAndLongAcrossZonesAndLocales() {
        for (ZoneId zone : List.of(ZoneId.of("UTC"), ZoneId.of("America/Chicago"), ZoneId.of("Europe/Berlin"))) {
            for (Locale locale : List.of(Locale.US, Locale.FRANCE, Locale.JAPAN)) {
                for (long epochMillis : MODERN_INSTANTS) {
                    assertSuffixes(new Date(epochMillis), CapturedTemporal.from(new Date(epochMillis)), zone, locale);
                    assertSuffixes(epochMillis, epochMillis, zone, locale);
                }
            }
        }
    }

    @Test
    void publicFormattingMatchesFormatterWidthAlignmentAndReuseInUtc() {
        withDefaults(ZoneId.of("UTC"), Locale.US, () -> {
            Date value = new Date(Instant.parse("2024-01-03T04:05:06Z").toEpochMilli());
            for (String pattern : List.of("%1$20tF", "%1$-20tF", "%1$tF %<tT", "%1$30Tc")) {
                BoundedMessageFormat.Result actual = BoundedMessageFormat.printf(pattern, new Object[] {value});

                assertFalse(actual.formatFailed(), pattern);
                assertEquals(String.format(Locale.US, pattern, value), actual.message(), pattern);
            }
        });
    }

    @Test
    void uppercaseTemporalPrefixMatchesFormatterForEverySuffixAndSafeValue() {
        for (Locale locale : List.of(Locale.US, Locale.FRANCE, Locale.forLanguageTag("tr-TR"))) {
            withDefaults(ZoneId.of("UTC"), locale, () -> {
                for (long epochMillis : MODERN_INSTANTS) {
                    for (Object value : List.of(new Date(epochMillis), epochMillis)) {
                        for (int index = 0; index < SUFFIXES.length(); index++) {
                            String pattern = "%1$T" + SUFFIXES.charAt(index);
                            BoundedMessageFormat.Result actual =
                                    BoundedMessageFormat.printf(pattern, new Object[] {value});
                            assertFalse(actual.formatFailed(), pattern);
                            assertEquals(String.format(locale, pattern, value), actual.message(), pattern + " / " + locale);
                        }
                    }
                }
            });
        }
    }

    @Test
    void invalidTemporalPrecisionAndFlagsTakeTheBoundedFailurePath() {
        Date value = new Date(0L);
        assertThrows(IllegalFormatPrecisionException.class, () -> String.format(Locale.US, "%.2tH", value));
        for (String pattern : List.of("%.2tH", "%#tH", "%0tH", "%-tH")) {
            assertTrue(BoundedMessageFormat.printf(pattern, new Object[] {value}).formatFailed(), pattern);
        }
    }

    @Test
    void duplicateReuseFlagsMatchFormatterFailureForOrdinaryAndTemporalConversions() {
        for (String pattern : List.of("%1$s %<<s", "%1$tH %<<tH")) {
            assertThrows(
                    DuplicateFormatFlagsException.class,
                    () -> String.format(Locale.US, pattern, new Date(0L)));
            assertTrue(BoundedMessageFormat.printf(pattern, new Object[] {new Date(0L)}).formatFailed(), pattern);
        }
    }

    @Test
    void ordinaryDateConversionsRetainFormatterSemanticsWithoutDateToString() {
        withDefaults(ZoneId.of("UTC"), Locale.US, () -> {
            Date value = new Date(123L);
            String pattern = "%1$s %1$S %1$h %1$b";
            BoundedMessageFormat.Result actual = BoundedMessageFormat.printf(pattern, new Object[] {value});

            assertFalse(actual.formatFailed());
            assertEquals(String.format(Locale.US, pattern, value), actual.message());
        });
    }

    @Test
    void preGregorianCutoverDatesFollowTheDocumentedProlepticPolicy() {
        long epochMillis = Instant.parse("1500-03-01T00:00:00Z").toEpochMilli();
        String safe = TrustedDateTimeRenderer.printf(
                new CapturedTemporal(epochMillis),
                'F',
                Locale.US,
                ZoneId.of("UTC"));

        withDefaults(ZoneId.of("UTC"), Locale.US, () ->
                assertNotEquals(String.format(Locale.US, "%tF", new Date(epochMillis)), safe));
        assertEquals("1500-03-01", safe);
    }

    @Test
    void safeTemporalSetIsExactlyDateAndEpochMillisecondLong() {
        assertFalse(BoundedMessageFormat.printf("%tF", new Object[] {new Date(0L)}).formatFailed());
        assertFalse(BoundedMessageFormat.printf("%tF", new Object[] {0L}).formatFailed());
        for (Object unsupported : List.of(
                Calendar.getInstance(),
                Instant.EPOCH,
                ZonedDateTime.parse("1970-01-01T00:00:00Z"),
                new Timestamp(0L))) {
            assertTrue(
                    BoundedMessageFormat.printf("%tF", new Object[] {unsupported}).formatFailed(),
                    unsupported.getClass().getName());
        }
    }

    private static void assertSuffixes(Object jdkValue, Object safeValue, ZoneId zone, Locale locale) {
        withDefaults(zone, locale, () -> {
            for (int index = 0; index < SUFFIXES.length(); index++) {
                char suffix = SUFFIXES.charAt(index);
                String expected = String.format(locale, "%1$t" + suffix, jdkValue);
                String actual = TrustedDateTimeRenderer.printf(safeValue, suffix, locale, zone);
                assertEquals(
                        expected,
                        actual,
                        "%t" + suffix + " differed for " + jdkValue + " in " + zone + " / " + locale);
            }
        });
    }

    private static void withDefaults(ZoneId zone, Locale locale, Runnable action) {
        TimeZone originalZone = TimeZone.getDefault();
        Locale originalLocale = Locale.getDefault(Locale.Category.FORMAT);
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            Locale.setDefault(Locale.Category.FORMAT, locale);
            action.run();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }
}
