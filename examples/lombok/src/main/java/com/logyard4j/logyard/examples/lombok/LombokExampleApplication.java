package com.logyard4j.logyard.examples.lombok;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LombokExampleApplication {
    private LombokExampleApplication() {
    }

    public static void main(String[] arguments) {
        String orderId = "order-1042";
        log.info("Lombok example started");
        log.info("Processing order id={} itemCount={}", orderId, 3);
        log.warn("Order id={} exceeded {} ms", orderId, 250);

        try {
            throw new IllegalStateException("expected Lombok example failure");
        } catch (IllegalStateException failure) {
            log.error("Order id={} failed", orderId, failure);
        }

        log.info("Lombok example shutdown flush");
    }
}
