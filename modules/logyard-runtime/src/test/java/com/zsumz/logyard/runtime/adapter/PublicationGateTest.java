package com.zsumz.logyard.runtime.adapter;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PublicationGateTest {
    @Test
    void retirementRejectsNewWorkAndWaitsForAdmittedWork() throws Exception {
        PublicationGate gate = new PublicationGate();
        assertTrue(gate.tryEnter());
        CountDownLatch completed = new CountDownLatch(1);
        Thread retirement = Thread.ofPlatform().start(() -> {
            assertTrue(gate.retireAndAwaitDrain(Duration.ofSeconds(1)));
            completed.countDown();
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!gate.retired() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(gate.retired());
        assertFalse(gate.tryEnter());
        assertFalse(completed.await(50, TimeUnit.MILLISECONDS));

        gate.exit();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        retirement.join();
    }
}
