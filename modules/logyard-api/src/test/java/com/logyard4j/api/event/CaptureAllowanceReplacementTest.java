package com.logyard4j.api.event;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaptureAllowanceReplacementTest {
    enum Exhausted {
        NODES, ENTRIES, TEXT
    }

    @ParameterizedTest
    @EnumSource(Exhausted.class)
    void replacementCannotRestoreOneExhaustedBudgetWhileOthersRemainFull(Exhausted exhausted) {
        CaptureContext source = CaptureContext.create();
        switch (exhausted) {
            case NODES -> {
                for (int index = 0; index < CaptureLimits.MAX_EVENT_NODES; index++) assertTrue(source.claimNode());
            }
            case ENTRIES -> {
                for (int index = 0; index < CaptureLimits.MAX_EVENT_ENTRIES; index++) assertTrue(source.claimEntry());
            }
            case TEXT -> source.capturePayloadText("x".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS),
                    CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        }

        CaptureContext replacement = CaptureContext.forAttributes(source.payloadAllowance());
        switch (exhausted) {
            case NODES -> {
                assertFalse(replacement.claimNode());
                assertTrue(replacement.claimEntry());
                assertEquals("ok", replacement.capturePayloadText("ok", 2));
            }
            case ENTRIES -> {
                assertTrue(replacement.claimNode());
                assertFalse(replacement.claimEntry());
                assertEquals("ok", replacement.capturePayloadText("ok", 2));
            }
            case TEXT -> {
                assertTrue(replacement.claimNode());
                assertTrue(replacement.claimEntry());
                assertEquals("", replacement.capturePayloadText("ok", 2));
            }
        }
    }
}
