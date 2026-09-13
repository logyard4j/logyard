package com.logyard4j.logyard.runtime.adapter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class AdapterReentryGuardTest {
    @Test
    void blocksReentryAcrossDistinctFacadeInstancesOnTheSameThread() {
        AdapterReentryGuard first = new AdapterReentryGuard();
        AdapterReentryGuard second = new AdapterReentryGuard();

        assertTrue(first.enter());
        try {
            assertFalse(second.enter());
        } finally {
            first.exit();
        }
        assertTrue(second.enter());
        second.exit();
    }

    @Test
    void keepsIndependentStateForDifferentThreads() throws Exception {
        AdapterReentryGuard guard = new AdapterReentryGuard();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean workerEntered = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            workerEntered.set(guard.enter());
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                guard.exit();
            }
        }, "logyard-reentry-guard-test");

        assertTrue(guard.enter());
        try {
            worker.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(workerEntered.get());
        } finally {
            guard.exit();
            release.countDown();
            worker.join(2_000L);
        }
        assertFalse(worker.isAlive());
    }
}
