package com.zsumz.logyard.runtime.reload;

import com.zsumz.logyard.api.reload.ReloadResult;
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
            return ReloadResult.APPLIED;
        });
        debouncer.signalChange();
        clock.set(114L);
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return ReloadResult.APPLIED;
        });
        assertEquals(0, reloads.get());

        clock.set(115L);
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return ReloadResult.APPLIED;
        });
        debouncer.runIfDue(() -> {
            reloads.incrementAndGet();
            return ReloadResult.APPLIED;
        });
        assertEquals(1, reloads.get());
    }

    @Test
    void keepsARejectedChangeDirtyUntilTheTransitionAcceptsIt() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger attempts = new AtomicInteger();
        ReloadDebouncer debouncer = new ReloadDebouncer(Duration.ofNanos(10L), clock::get);

        debouncer.signalChange();
        clock.set(110L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0 ? ReloadResult.REJECTED : ReloadResult.APPLIED);
        clock.set(119L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0 ? ReloadResult.REJECTED : ReloadResult.APPLIED);
        assertEquals(1, attempts.get());

        clock.set(120L);
        debouncer.runIfDue(() -> attempts.getAndIncrement() == 0 ? ReloadResult.REJECTED : ReloadResult.APPLIED);
        assertEquals(2, attempts.get());
    }
}
