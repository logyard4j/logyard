package com.logyard4j.compare;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProducerTeamTest {
    @Test
    void everyRequestUsesOneFreshVirtualThreadWithBoundedConcurrencyAndItsOwnMdc() throws Exception {
        MDC.clear();
        RunOptions options = options("per-request", "0");
        Set<Long> callers = ConcurrentHashMap.newKeySet();
        Set<Integer> identities = ConcurrentHashMap.newKeySet();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(options.producers());
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("info") && !method.getName().equals("error")) return null;
                    assertTrue(Thread.currentThread().isVirtual());
                    assertTrue(callers.add(Thread.currentThread().threadId()), "request reused a caller thread");
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        firstWave.countDown();
                        assertTrue(firstWave.await(5, TimeUnit.SECONDS));
                        assertEquals(options.fields(), MDC.getCopyOfContextMap().size());
                        assertEquals("field.0-value\"\\\tλ", MDC.get("field.0"));
                        identities.add(Integer.parseInt(((String) arguments[0]).substring(0, 8), 16));
                    } finally {
                        active.decrementAndGet();
                    }
                    return null;
                });
        try (ProducerTeam producers = new ProducerTeam(options, new Workload(options), logger)) {
            producers.awaitWarmup();
            callers.clear();
            identities.clear();
            long started = producers.begin();
            producers.awaitCalls();
            assertTrue(producers.awaitCalls(1, TimeUnit.SECONDS));
            assertEquals(options.events(), callers.size());
            assertEquals(options.events(), identities.size());
            assertEquals(options.producers(), peak.get());
            assertTrue(producers.threadIds().isEmpty(), "dispatchers must not be reported as virtual callers");
            for (int index = 0; index < options.events(); index++) {
                assertTrue(producers.arrivals()[index] >= started);
                assertTrue(producers.callerTimes()[index] > 0);
            }
        }
        assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
    }

    @Test
    void closingScheduledProducersCancelsWaitsAndCannotProduceAValidMeasurement() throws Exception {
        RunOptions options = options("per-request", "2");
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, arguments) -> null);
        try (ProducerTeam producers = new ProducerTeam(options, new Workload(options), logger)) {
            producers.awaitWarmup();
            producers.begin();
            assertFalse(producers.awaitCalls(1, TimeUnit.MILLISECONDS));
            long started = System.nanoTime();
            producers.close();
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
            assertThrows(IllegalStateException.class, producers::awaitCalls);
            assertThrows(IllegalStateException.class, () -> producers.awaitCalls(1, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class, producers::begin);
        }
    }

    @Test
    void rejectsUnknownThreadModelsInsteadOfSilentlySelectingPlatformThreads() {
        assertEquals(ThreadModel.PLATFORM, options("false", "0").threadModel());
        assertEquals(ThreadModel.VIRTUAL, options("true", "0").threadModel());
        assertEquals(ThreadModel.VIRTUAL_PER_REQUEST, options("per-request", "0").threadModel());
        assertThrows(IllegalArgumentException.class, () -> options("typo", "0"));
    }

    private static RunOptions options(String model, String rate) {
        return RunOptions.parse(new String[] {"unused.jsonl", "64", "4", "2", "4", "json", rate,
                "0", "0", model, "matched-drop", "none"});
    }
}
