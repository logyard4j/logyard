package com.logyard4j.logyard.test;

import com.logyard4j.logyard.api.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EventExpectationTest {
    @Test
    void anEmptyExpectationMatchesEveryRecordedEvent() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Any").info("one");
            kit.logger("other.Any").warn("two");
            kit.events().expect().assertCount(2);
            kit.events().expect().assertPresent();
            assertThrows(AssertionError.class, () -> kit.events().expect().assertNone());
        }
    }

    @Test
    void levelFiltersByExactSeverity() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Level").debug("low");
            kit.logger("test.Level").error("high");
            kit.events().expect().level(Level.DEBUG).assertCount(1);
            kit.events().expect().level(Level.ERROR).assertCount(1);
            kit.events().expect().level(Level.INFO).assertNone();
        }
    }

    @Test
    void loggerFiltersByExactName() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("com.example.Parent").info("parent");
            kit.logger("com.example.Parent.Child").info("child");
            kit.events().expect().logger("com.example.Parent").assertCount(1);
            kit.events().expect().logger("com.example").assertNone();
        }
    }

    @Test
    void eventNameFiltersByStableName() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Named").atInfo().event("order.placed").log("named");
            kit.logger("test.Named").info("unnamed");
            kit.events().expect().eventName("order.placed").assertCount(1);
            kit.events().expect().eventName("order.cancelled").assertNone();
        }
    }

    @Test
    void messageContainsMatchesTheRenderedMessage() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Message").info("order {} for {}", 7L, "north");
            kit.events().expect().messageContains("order 7 for north").assertPresent();
            kit.events().expect().messageContains("{}").assertNone();
        }
    }

    @Test
    void attributePresenceIgnoresTheValueAndAcceptsCapturedNulls() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Attributes").atInfo().add("order.id", 7L).add("missing", null).log("attributed");
            kit.logger("test.Attributes").info("bare");
            kit.events().expect().attribute("order.id").assertCount(1);
            kit.events().expect().attribute("missing").assertCount(1);
            kit.events().expect().attribute("absent").assertNone();
        }
    }

    @Test
    void attributeValuesCompareByEqualityAndType() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Values").atInfo().add("order.id", 7L).add("tenant", "north").log("valued");
            kit.events().expect().attribute("order.id", 7L).assertPresent();
            kit.events().expect().attribute("order.id", 7).assertNone();
            kit.events().expect().attribute("tenant", "south").assertNone();
        }
    }

    @Test
    void attributeCriteriaCompose() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Compose").atInfo().add("tenant", "north").add("region", "eu").log("both");
            kit.logger("test.Compose").atInfo().add("tenant", "north").log("one");
            kit.events().expect().attribute("tenant", "north").assertCount(2);
            kit.events().expect().attribute("tenant", "north").attribute("region", "eu").assertCount(1);
            kit.events().expect().attribute("tenant", "north").attribute("region", "us").assertNone();
        }
    }

    @Test
    void exceptionTypeMatchesTheCapturedTypeExactly() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Exception").atError().cause(new IllegalStateException("broken")).log("failed");
            kit.logger("test.Exception").info("fine");
            kit.events().expect().exceptionType(IllegalStateException.class).assertCount(1);
            // The event carries a detached snapshot naming one type, so a supertype does not match.
            kit.events().expect().exceptionType(RuntimeException.class).assertNone();
        }
    }

    @Test
    void criteriaCombineAcrossDimensions() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Mixed").atWarn().event("retry").add("attempt", 2).log("retrying {}", "payment");
            kit.events().expect()
                    .level(Level.WARN)
                    .logger("test.Mixed")
                    .eventName("retry")
                    .messageContains("retrying payment")
                    .attribute("attempt", 2)
                    .assertPresent();
            kit.events().expect().level(Level.WARN).logger("test.Other").assertNone();
        }
    }

    @Test
    void repeatingASingleValuedCriterionReplacesIt() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Replace").warn("replaced");
            kit.events().expect().level(Level.INFO).level(Level.WARN).assertPresent();
        }
    }

    @Test
    void expectationsAreIndependentOfEachOther() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            kit.logger("test.Independent").info("first");
            kit.logger("test.Independent").warn("second");
            EventExpectation warnings = kit.events().expect().level(Level.WARN);
            kit.events().expect().level(Level.INFO).assertCount(1);
            warnings.assertCount(1);
        }
    }

    @Test
    void assertCountRejectsANegativeExpectation() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> kit.events().expect().assertCount(-1));
            assertEquals("expected match count must not be negative", rejected.getMessage());
        }
    }

    @Test
    void terminalsReadTheRecorderAtCallTime() {
        try (LogyardTestKit kit = LogyardTestKit.isolated()) {
            EventExpectation expectation = kit.events().expect().logger("test.Later");
            expectation.assertNone();
            kit.logger("test.Later").info("published after the builder was created");
            assertDoesNotThrow(expectation::assertPresent);
        }
    }
}
