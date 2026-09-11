package com.logyard4j.test;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KitIsolationTest {
    private static final int KITS = 4;
    private static final int EVENTS_PER_KIT = 50;

    @Test
    void concurrentKitsCaptureOnlyTheirOwnEvents() throws InterruptedException {
        List<LogyardTestKit> kits = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(KITS);
        for (int index = 0; index < KITS; index++) {
            LogyardTestKit kit = LogyardTestKit.isolated();
            kits.add(kit);
            threads.add(publisher(kit, "kit-" + index, start, done, failure));
        }
        try {
            threads.forEach(Thread::start);
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "publishing threads must finish");
            Throwable thrown = failure.get();
            if (thrown != null) {
                throw new AssertionError("publishing failed", thrown);
            }
            for (int index = 0; index < KITS; index++) {
                assertOwnEventsOnly(kits.get(index), "kit-" + index);
            }
        } finally {
            kits.forEach(LogyardTestKit::close);
        }
    }

    @Test
    void closingOneKitLeavesAnotherRecording() {
        try (LogyardTestKit retained = LogyardTestKit.isolated()) {
            LogyardTestKit discarded = LogyardTestKit.isolated();
            discarded.logger("test.Discarded").info("before the other kit closes");
            discarded.close();

            retained.logger("test.Retained").info("still recording");
            assertEquals(1, retained.events().size());
            assertEquals(1, discarded.events().size(), "a closed kit keeps what it already recorded");
            retained.events().expect().messageContains("still recording").assertPresent();
            retained.events().expect().messageContains("before the other kit closes").assertNone();
        }
    }

    @Test
    void kitsDoNotShareRecorders() {
        try (LogyardTestKit first = LogyardTestKit.isolated();
                LogyardTestKit second = LogyardTestKit.isolated()) {
            first.logger("test.Shared").info("only in the first kit");
            assertEquals(0, second.events().size());
            second.events().expect().assertNone();
            first.events().expect().assertCount(1);
        }
    }

    private static Thread publisher(
            LogyardTestKit kit,
            String owner,
            CountDownLatch start,
            CountDownLatch done,
            AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                assertTrue(start.await(30, TimeUnit.SECONDS), "publishers must be released together");
                for (int index = 0; index < EVENTS_PER_KIT; index++) {
                    kit.logger("test.Parallel").atInfo().add("owner", owner).log("event {}", index);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(null, interrupted);
            } catch (RuntimeException | AssertionError problem) {
                failure.compareAndSet(null, problem);
            } finally {
                done.countDown();
            }
        }, owner);
        thread.setDaemon(true);
        return thread;
    }

    private static void assertOwnEventsOnly(LogyardTestKit kit, String owner) {
        List<LogEvent> events = kit.events().all();
        assertEquals(EVENTS_PER_KIT, events.size(), owner + " must capture exactly its own events");
        kit.events().expect().level(Level.INFO).attribute("owner", owner).assertCount(EVENTS_PER_KIT);
        for (LogEvent event : events) {
            assertEquals(owner, event.attributes().toMap().get("owner"));
        }
    }
}
