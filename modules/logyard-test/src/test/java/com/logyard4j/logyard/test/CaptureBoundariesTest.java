package com.logyard4j.logyard.test;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CaptureBoundariesTest {
    @Test
    void overflowCannotMakeAnyAssertionPassOnAnIncompleteCapture() {
        try (LogyardTestKit kit = LogyardTestKit.isolated(1)) {
            kit.logger("test").info("retained");
            kit.events().expect().assertCount(1);
            kit.logger("test").error("overflow");
            assertThrows(AssertionError.class, () -> kit.events().size());
            assertThrows(AssertionError.class, () -> kit.events().all());
            assertThrows(AssertionError.class, () -> kit.events().expect().assertPresent());
            assertThrows(AssertionError.class, () -> kit.events().expect().assertCount(1));
            AssertionError error = assertThrows(AssertionError.class,
                    () -> kit.events().expect().messageContains("overflow").assertNone());
            assertTrue(error.getMessage().contains("increase LogyardTestKit.isolated(capacity)"));
            kit.events().clear();
            kit.logger("test").info("next phase");
            kit.events().expect().messageContains("next phase").assertCount(1);
        }
    }

    @Test
    void defaultCapacityIsFiniteAndInvalidCapacityIsRejected() {
        assertEquals(1024, LogyardTestKit.DEFAULT_CAPACITY);
        assertThrows(IllegalArgumentException.class, () -> LogyardTestKit.isolated(0));
        assertThrows(IllegalArgumentException.class, () -> LogyardTestKit.isolated(-1));
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            for (int index = 0; index <= LogyardTestKit.DEFAULT_CAPACITY; index++) {
                kit.logger("test").info("record");
            }
            assertThrows(AssertionError.class, () -> kit.events().all());
        }
    }

    @Test
    void concurrentOverflowIsStickyUntilClear() throws Exception {
        try (LogyardTestKit kit = LogyardTestKit.isolated(10);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> logMany(kit));
            var second = workers.submit(() -> logMany(kit));
            first.get();
            second.get();
            assertThrows(AssertionError.class, () -> kit.events().all());
            kit.events().clear();
            kit.events().expect().assertNone();
        }
    }

    @Test
    void diagnosticsKeepIdentitiesAndMessagesOnOneTerminalSafeLine() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            String hostile = "name\r\n\u001b[2J\u2028\u202e";
            kit.logger(hostile).atInfo().event(hostile).add(hostile, "value").log(hostile);
            AssertionError failure = assertThrows(AssertionError.class, () -> kit.events().expect()
                    .logger(hostile).eventName(hostile).attribute(hostile, hostile).assertPresent());
            String message = failure.getMessage();
            assertEquals(4, message.lines().count());
            String terminalContent = message.replace(System.lineSeparator(), "");
            for (char control : new char[]{'\r', '\u001b', '\u2028', '\u202e'}) {
                assertFalse(terminalContent.contains(String.valueOf(control)), message);
            }
            assertTrue(message.contains("\\n"), message);
        }
    }

    @Test
    void diagnosticTruncationDoesNotSplitSupplementaryCharacters() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test").info("x".repeat(118) + "😀".repeat(20));
            String message = assertThrows(AssertionError.class,
                    () -> kit.events().expect().assertNone()).getMessage();
            assertTrue(message.contains("x".repeat(118) + "…"), message);
            assertFalse(message.chars().anyMatch(c -> Character.isSurrogate((char) c)));
        }
    }

    private static void logMany(LogyardTestKit kit) {
        for (int index = 0; index < 100; index++) kit.logger("test").info("record");
    }
}
