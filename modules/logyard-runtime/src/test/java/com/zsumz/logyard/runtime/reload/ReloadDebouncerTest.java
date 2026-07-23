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
        debouncer.runIfDue(reloads::incrementAndGet);
        debouncer.signalChange();
        clock.set(114L);
        debouncer.runIfDue(reloads::incrementAndGet);
        assertEquals(0, reloads.get());

        clock.set(115L);
        debouncer.runIfDue(reloads::incrementAndGet);
        debouncer.runIfDue(reloads::incrementAndGet);
        assertEquals(1, reloads.get());
    }
}
