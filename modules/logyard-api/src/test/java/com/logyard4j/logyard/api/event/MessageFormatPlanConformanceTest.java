package com.logyard4j.logyard.api.event;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class MessageFormatPlanConformanceTest {
    private static final String[] FRAGMENTS = {
            "plain", " ", "|", "''", "'{'", "'}'", "'{0}'", "{0}", "{1}", "{2}", "{3}", "{4}",
            "{0,number}", "{0,number,integer}", "{0,number,000.0}", "{0,number,'x'000'!'}",
            "{0,number,#,##0.00}", "{0,choice,0#zero|1#one|1<{1}}", "{2,choice,0#nil|1#{3}}",
            "{0,choice,0#none|1#{2,number,000}}", "{4,choice,0#missing|1#present}",
            "{١}", "{１}", "{+1}", "{-0}", "{+0,number,000}", "{٠,choice,0#none|1#{+2,number,000}}"
    };

    @ParameterizedTest
    @MethodSource("locales")
    void boundedGeneratedPatternsMatchTheJdkReference(Locale locale) {
        Object[] selectors = {-1, 0, 1, 2, 12.5, Double.NaN};
        Object[] values = {null, "value", "𐐀\n{literal}", true, 7L};
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, locale);
            Random random = new Random(0x4c6f6779617264L);
            for (int sample = 0; sample < 5_000; sample++) {
                StringBuilder pattern = new StringBuilder();
                int elements = 1 + random.nextInt(8);
                for (int index = 0; index < elements; index++) {
                    pattern.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
                }
                Object[] arguments = {
                        selectors[random.nextInt(selectors.length)], values[random.nextInt(values.length)],
                        sample % 3 == 0 ? null : sample % 4, values[random.nextInt(values.length)]
                };
                String template = pattern.toString();
                String expected = new MessageFormat(template, locale).format(arguments);
                BoundedMessageFormat.Result actual = BoundedMessageFormat.messageFormat(template, arguments);
                String description = locale + " sample=" + sample + " pattern=" + template;

                assertFalse(actual.formatFailed(), description);
                assertFalse(actual.truncated(), description);
                assertEquals(expected, actual.message(), description);
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    private static Stream<Locale> locales() {
        return Stream.of(Locale.US, Locale.FRANCE, Locale.JAPAN);
    }
}
