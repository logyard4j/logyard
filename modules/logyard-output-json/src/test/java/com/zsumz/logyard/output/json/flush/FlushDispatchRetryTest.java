package com.zsumz.logyard.output.json.flush;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FlushDispatchRetryTest {
    @Test
    void growsToABoundedDelayAndResetsAfterSuccess() {
        FlushDispatchRetry retry = new FlushDispatchRetry();

        assertEquals(Duration.ofMillis(10L), retry.nextDelay());
        assertEquals(Duration.ofMillis(50L), retry.nextDelay());
        assertEquals(Duration.ofMillis(250L), retry.nextDelay());
        assertEquals(Duration.ofSeconds(1L), retry.nextDelay());
        assertEquals(Duration.ofSeconds(1L), retry.nextDelay());

        retry.reset();

        assertEquals(Duration.ofMillis(10L), retry.nextDelay());
    }
}
