package com.zsumz.logyard.runtime.reload;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ReloadDebouncerTest {
    @Test
    void waitsForQuietTimeAndRunsOnlyOnce() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger reloads = new AtomicInteger();
        ReloadDebouncer debouncer = new ReloadDebouncer(Duration.ofNanos(10L), clock::get);

        debouncer.signalChange();
        clock.set(105L);
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return WatcherReloadOutcome.APPLIED;
        });
        debouncer.signalChange();
        clock.set(114L);
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return WatcherReloadOutcome.APPLIED;
        });
        assertEquals(0, reloads.get());

        clock.set(115L);
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return WatcherReloadOutcome.APPLIED;
        });
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return WatcherReloadOutcome.APPLIED;
        });
        assertEquals(1, reloads.get());
    }

    @Test
    void keepsABusyChangeDirtyUntilTheTransitionAcceptsIt() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger attempts = new AtomicInteger();
        ReloadDebouncer debouncer = new ReloadDebouncer(Duration.ofNanos(10L), clock::get);

        debouncer.signalChange();
        clock.set(110L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0
                ? WatcherReloadOutcome.BUSY_RETRY
                : WatcherReloadOutcome.APPLIED);
        clock.set(119L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0
                ? WatcherReloadOutcome.BUSY_RETRY
                : WatcherReloadOutcome.APPLIED);
        assertEquals(1, attempts.get());

        clock.set(120L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0
                ? WatcherReloadOutcome.BUSY_RETRY
                : WatcherReloadOutcome.APPLIED);
        assertEquals(2, attempts.get());
    }

    @Test
    void invalidCandidateWaitsForAnotherFileEvent() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger attempts = new AtomicInteger();
        ReloadDebouncer debouncer = new ReloadDebouncer(Duration.ofNanos(10L), clock::get);

        debouncer.signalChange();
        clock.set(110L);
        debouncer.runIfDue(() -> {
            attempts.incrementAndGet();
            return WatcherReloadOutcome.WAIT_FOR_CHANGE;
        });
        clock.set(1_000L);
        debouncer.runIfDue(() -> {
            attempts.incrementAndGet();
            return WatcherReloadOutcome.WAIT_FOR_CHANGE;
        });

        assertEquals(1, attempts.get());
    }

    @Test
    void transientFailuresRemainDirtyWithCappedExponentialRetriesUntilApplied() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger attempts = new AtomicInteger();
        ReloadDebouncer debouncer = new ReloadDebouncer(
                Duration.ofNanos(10L),
                Duration.ofNanos(10L),
                Duration.ofNanos(40L),
                clock::get);

        debouncer.signalChange();
        for (long due : new long[] {110L, 120L, 140L, 180L, 220L, 260L, 1_000L}) {
            clock.set(due);
            debouncer.runIfDue(() -> {
                return attempts.incrementAndGet() < 6
                        ? WatcherReloadOutcome.TRANSIENT_RETRY
                        : WatcherReloadOutcome.APPLIED;
            });
        }

        assertEquals(6, attempts.get());
    }
}
