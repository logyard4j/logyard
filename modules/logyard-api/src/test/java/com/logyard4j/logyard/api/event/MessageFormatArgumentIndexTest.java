package com.logyard4j.logyard.api.event;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.MessageFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MessageFormatArgumentIndexTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{١}", "{１}", "{+1}", "{-0}", "{٠١}", "{+٠}", "{+0,number}", "{١,number,000}",
            "{+0,choice,0#none|1#{１}}", "{٠,choice,0#none|1#{+1,number,000}}"
    })
    void validIndexSpellingsSelectTheSameArgumentAsTheJdk(String pattern) {
        assertMatchesJdk(pattern, new Object[] {1, 42});
    }

    @ParameterizedTest
    @ValueSource(strings = {"١", "１", "+1", "-0"})
    void dateTimeSanitizingPreservesTheSelectedIndex(String index) {
        Object[] arguments = {new Date(0), new Date(90_000_000)};
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertMatchesJdk("{" + index + ",date,short}", arguments);
            assertMatchesJdk("{" + index + ",time,short}", arguments);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {" 0", "0 ", "0x", "+", "-1", "2147483648", ""})
    void invalidIndicesRemainInvalidWhenDateTimeFormatsAreSanitized(String index) {
        String pattern = "{" + index + ",date,short}";
        assertThrows(IllegalArgumentException.class, () -> new MessageFormat(pattern));

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(pattern, new Object[] {new Date(0)});

        assertTrue(result.formatFailed());
        assertEquals(pattern, result.message());
    }

    private static void assertMatchesJdk(String pattern, Object[] arguments) {
        String expected = new MessageFormat(pattern, Locale.getDefault(Locale.Category.FORMAT)).format(arguments);

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(pattern, arguments);

        assertFalse(result.formatFailed(), pattern);
        assertFalse(result.truncated(), pattern);
        assertEquals(expected, result.message(), pattern);
    }
}
