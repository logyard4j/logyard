package com.logyard4j.api.diagnostics;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiagnosticRateLimiterTest {
    @Test
    void countsSuppressedReportsUntilTheNextSuccessfulDrain() {
        DiagnosticRateLimiter limiter = new DiagnosticRateLimiter(Duration.ofDays(1L));

        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        assertEquals(2L, limiter.drainSuppressed());
        assertEquals(0L, limiter.drainSuppressed());
    }

    @Test
    void rejectsNegativeIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticRateLimiter(Duration.ofNanos(-1L)));
    }
}
