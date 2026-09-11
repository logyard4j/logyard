package com.zsumz.logyard.examples.testing;

import com.zsumz.logyard.api.LogyardLogger;

/** An application service receiving its logger through its constructor. */
public final class Checkout {
    private final LogyardLogger logger;

    public Checkout(LogyardLogger logger) {
        this.logger = logger;
    }

    public void accept(long orderId) {
        logger.atInfo().event("order.accepted")
                .addLazy("order.id", () -> orderId).log("order accepted");
    }
}
