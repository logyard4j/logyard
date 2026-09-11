package com.zsumz.logyard.examples.testing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.context.LogContext;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.test.LogyardTestKit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class CheckoutTest {
    @Test
    void capturesScopedAndPresetAttributesWithTheOrder() {
        try (LogyardTestKit kit = LogyardTestKit.isolated();
                var scope = LogContext.push(AttributeSet.builder().put("request.id", "r-1").build())) {
            Checkout checkout = new Checkout(kit.logger(Checkout.class).with(
                    AttributeSet.builder().put("component", "checkout").build()));
            checkout.accept(7L);
            kit.events().expect().level(Level.INFO).eventName("order.accepted")
                    .attribute("request.id", "r-1").attribute("component", "checkout")
                    .attribute("order.id", 7L).assertCount(1);
            kit.events().expect().level(Level.ERROR).assertNone();
        }
    }

    @Test
    void refusesAssertionsAfterCaptureOverflow() {
        try (LogyardTestKit kit = LogyardTestKit.isolated(1)) {
            Checkout checkout = new Checkout(kit.logger(Checkout.class));
            checkout.accept(7L);
            checkout.accept(8L);
            assertThrows(AssertionError.class,
                    () -> kit.events().expect().attribute("order.id", 8L).assertNone());
            kit.events().clear();
            checkout.accept(9L);
            kit.events().expect().attribute("order.id", 9L).assertCount(1);
        }
    }
}
