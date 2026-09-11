package com.zsumz.logyard.test;

import com.zsumz.logyard.api.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExpectationFailureMessageTest {
    private static final int DUMP_LIMIT = 20;

    @Test
    void aMissingMatchNamesEveryCriterionAndDumpsTheRecordedEvents() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Failure").atInfo().add("tenant", "north").log("recorded message");
            AssertionError failure = assertThrows(AssertionError.class, () -> kit.events().expect()
                    .level(Level.ERROR)
                    .logger("test.Other")
                    .eventName("order.placed")
                    .messageContains("absent")
                    .exceptionType(IllegalStateException.class)
                    .attribute("region")
                    .attribute("tenant", "south")
                    .assertPresent());
            String message = failure.getMessage();
            assertTrue(message.contains("expected at least one matching event, found none"), message);
            assertTrue(message.contains("level=ERROR"), message);
            assertTrue(message.contains("logger=test.Other"), message);
            assertTrue(message.contains("eventName=order.placed"), message);
            assertTrue(message.contains("messageContains=\"absent\""), message);
            assertTrue(message.contains("exceptionType=java.lang.IllegalStateException"), message);
            assertTrue(message.contains("attribute[region] present"), message);
            assertTrue(message.contains("attribute[tenant]=south"), message);
            assertTrue(message.contains("captured 1 event(s)"), message);
            assertTrue(message.contains("INFO test.Failure"), message);
            assertTrue(message.contains("message=\"recorded message\""), message);
            assertTrue(message.contains("attributes=[tenant]"), message);
        }
    }

    @Test
    void anEmptyRecorderStillReportsTheCriteria() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            AssertionError failure = assertThrows(
                    AssertionError.class,
                    () -> kit.events().expect().level(Level.INFO).assertPresent());
            assertTrue(failure.getMessage().contains("criteria: level=INFO"), failure.getMessage());
            assertTrue(failure.getMessage().contains("captured 0 event(s)"), failure.getMessage());
        }
    }

    @Test
    void anEmptyExpectationDescribesItselfWithoutCriteria() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Any").info("recorded");
            AssertionError failure = assertThrows(AssertionError.class, () -> kit.events().expect().assertNone());
            assertTrue(failure.getMessage().contains("expected no matching event, found 1"), failure.getMessage());
            assertTrue(failure.getMessage().contains("criteria: any recorded event"), failure.getMessage());
        }
    }

    @Test
    void aCountMismatchReportsBothCounts() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Count").info("one");
            kit.logger("test.Count").info("two");
            AssertionError failure = assertThrows(
                    AssertionError.class,
                    () -> kit.events().expect().logger("test.Count").assertCount(3));
            assertTrue(failure.getMessage().contains("expected exactly 3 matching event(s), found 2"),
                    failure.getMessage());
        }
    }

    @Test
    void theDumpCapsAtTwentyEventsAndReportsTheRemainder() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            for (int index = 0; index < DUMP_LIMIT + 5; index++) {
                kit.logger("test.Dump").atInfo().log("event {}", index);
            }
            assertEquals(DUMP_LIMIT + 5, kit.events().size());
            AssertionError failure = assertThrows(AssertionError.class, () -> kit.events().expect().assertNone());
            String message = failure.getMessage();
            assertTrue(message.contains("captured 25 event(s), showing 20:"), message);
            assertTrue(message.contains("... 5 further event(s) not shown"), message);
            assertTrue(message.contains("[19] INFO test.Dump"), message);
            assertFalse(message.contains("[20] INFO test.Dump"), message);
            assertEquals(DUMP_LIMIT, countLines(message, "] INFO test.Dump"));
        }
    }

    @Test
    void theDumpTruncatesALongRenderedMessage() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            String longMessage = "x".repeat(200);
            kit.logger("test.Truncate").info(longMessage);
            AssertionError failure = assertThrows(AssertionError.class, () -> kit.events().expect().assertNone());
            String message = failure.getMessage();
            assertTrue(message.contains("message=\"" + "x".repeat(119) + "…\""), message);
            assertFalse(message.contains("x".repeat(121)), "the dump must not carry the untruncated message");
        }
    }

    private static int countLines(String message, String marker) {
        int occurrences = 0;
        for (String line : message.split("\\R")) {
            if (line.contains(marker)) {
                occurrences++;
            }
        }
        return occurrences;
    }
}
