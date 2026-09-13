package com.logyard4j.logyard.api.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaptureTextAccountingTest {
    enum Field {
        PAYLOAD, IDENTITY, TEMPLATE;

        String capture(CaptureContext context, String value, int limit) {
            return switch (this) {
                case PAYLOAD -> context.capturePayloadText(value, limit);
                case IDENTITY -> context.captureIdentityText(value, limit);
                case TEMPLATE -> context.captureTemplateText(value, limit);
            };
        }
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void nullAndExactLimitTextRemainUnshortened(Field field) {
        CaptureContext context = CaptureContext.create();
        String value = "a😀b";
        assertNull(field.capture(context, null, 0));
        assertSame(value, field.capture(context, value, 4));
        assertFalse(context.truncated());
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void truncationIsSurrogateSafeAndRemainsSticky(Field field) {
        String[] expected = {"", "…", "a…", "a…"};
        for (int limit = 0; limit < expected.length; limit++) {
            CaptureContext context = CaptureContext.create();
            assertEquals(expected[limit], field.capture(context, "a😀b", limit));
            assertTrue(context.truncated());
            assertEquals("ok", field.capture(context, "ok", 2));
            assertTrue(context.truncated());
        }
    }

    @Test
    void payloadBudgetChargesCapturedTextAcrossFields() {
        CaptureContext context = CaptureContext.forAttributes(new CaptureAllowance(8, 8, 5));
        assertEquals("abc", context.capturePayloadText("abc", 10));
        assertEquals(2, context.remainingPayloadCharacters());
        assertFalse(context.truncated());
        assertEquals("d…", context.capturePayloadText("def", 10));
        assertEquals(0, context.remainingPayloadCharacters());
        assertEquals("", context.capturePayloadText("more", 10));
        assertNull(context.capturePayloadText(null, 10));
        assertTrue(context.truncated());
    }
}
