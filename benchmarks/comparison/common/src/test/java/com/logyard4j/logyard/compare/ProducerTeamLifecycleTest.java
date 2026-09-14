package com.logyard4j.logyard.compare;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProducerTeamLifecycleTest {
    @ParameterizedTest
    @ValueSource(strings = {"false", "true"})
    void repeatedMeasurementsKeepCallersAliveUntilCloseAndThenJoinEveryCaller(String model) throws Exception {
        RunOptions options = RunOptions.parse(new String[] {"unused.jsonl", "64", "4", "2", "4", "json", "0",
                "0", "0", model, "matched-drop", "none"});
        Logger logger = LoggerFactory.getLogger("comparison.lifecycle-test");
        for (int cycle = 0; cycle < 32; cycle++) {
            Set<Thread> callers = ConcurrentHashMap.newKeySet();
            AtomicInteger calls = new AtomicInteger();
            ProducerWorkload workload = new ProducerWorkload() {
                @Override
                public void prepareThread() {
                    callers.add(Thread.currentThread());
                }

                @Override
                public void log(Logger logger, int index) {
                    calls.incrementAndGet();
                }
            };
            try (ProducerTeam producers = new ProducerTeam(options, workload, logger)) {
                producers.awaitWarmup();
                assertEquals(10_000, calls.get());
                calls.set(0);
                producers.begin();
                producers.awaitCalls();
                assertEquals(options.events(), calls.get());
                assertEquals(options.producers(), callers.size());
                assertTrue(callers.stream().allMatch(Thread::isAlive), "callers exited before metrics sampling");
            }
            assertFalse(callers.stream().anyMatch(Thread::isAlive), "close left a live producer");
        }
    }
}
