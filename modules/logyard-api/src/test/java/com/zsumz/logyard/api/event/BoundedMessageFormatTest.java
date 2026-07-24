package com.zsumz.logyard.api.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedMessageFormatTest {
    @Test
    void rendersValidMessageFormatNumberAndChoicePatterns() {
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(
                "There {0,choice,0#are no files|1#is one file|1<are {0,number,integer} files}",
                new Object[] {42});

        assertEquals("There are 42 files", result.message());
        assertFalse(result.formatFailed());
    }

    @Test
    void rejectsInvalidMessageFormatWithoutLosingTheTemplate() {
        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat("broken {0", new Object[] {"value"});

        assertEquals("broken {0", result.message());
        assertTrue(result.formatFailed());
    }

    @Test
    void boundsExtremeNumbersAndHostileObjects() {
        Object hostile = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        };
        BigDecimal extreme = new BigDecimal(BigInteger.ONE, -100_000_000);
        BigInteger huge = BigInteger.ONE.shiftLeft(1_000_000);

        BoundedMessageFormat.Result result =
                BoundedMessageFormat.messageFormat("{0} {1} {2}", new Object[] {extreme, huge, hostile});

        assertTrue(result.message().contains("1E+100000000"));
        assertTrue(result.message().contains("numeric value omitted"));
        assertTrue(result.message().contains("FAILED toString()"));
        assertTrue(result.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
    }

    @Test
    void boundsHugeTemplatesAndPrintfFields() {
        BoundedMessageFormat.Result messageFormat =
                BoundedMessageFormat.messageFormat("{0}".repeat(100_000), new Object[] {"value"});
        BoundedMessageFormat.Result printf =
                BoundedMessageFormat.printf("%100000000s", new Object[] {"value"});

        assertTrue(messageFormat.template().length() <= CaptureLimits.MAX_EVENT_TEMPLATE_CHARS);
        assertTrue(messageFormat.message().length() <= CaptureLimits.MAX_RENDERED_MESSAGE_CHARS);
        assertTrue(messageFormat.truncated());
        assertTrue(printf.message().length() <= CaptureLimits.MAX_CAPTURED_NUMBER_CHARS);
        assertTrue(printf.truncated());
    }
}
