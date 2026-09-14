package com.logyard4j.logyard.compare;

import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("Fault injection temporarily replaces SLF4J's process-wide MDC adapter")
final class ProducerTeamCleanupFailureTest {
    @ParameterizedTest
    @ValueSource(strings = {"false", "true", "per-request"})
    void cleanupFailureInvalidatesTheRunAndStillReleasesAllWaiters(String model) throws Exception {
        Logger logger = LoggerFactory.getLogger("comparison.cleanup-test");
        MDCAdapter original = MDC.getMDCAdapter();
        Field adapter = MDC.class.getDeclaredField("MDC_ADAPTER");
        adapter.setAccessible(true);
        IllegalStateException injected = new IllegalStateException("injected MDC cleanup failure");
        MDCAdapter failing = (MDCAdapter) Proxy.newProxyInstance(MDCAdapter.class.getClassLoader(),
                new Class<?>[] {MDCAdapter.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("clear")) throw injected;
                    return null;
                });
        ConcurrentLinkedQueue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
        ProducerWorkload workload = new ProducerWorkload() {
            @Override
            public void prepareThread() {
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
            }

            @Override
            public void log(Logger ignored, int index) {
            }
        };
        RunOptions options = RunOptions.parse(new String[] {"unused.jsonl", "64", "4", "2", "4", "json", "0",
                "0", "0", model, "matched-drop", "none"});
        try {
            adapter.set(null, failing);
            ProducerTeam producers = new ProducerTeam(options, workload, logger);
            try {
                if (options.virtualPerRequest()) {
                    assertSame(injected, assertThrows(IllegalStateException.class, producers::awaitWarmup).getCause());
                } else {
                    producers.awaitWarmup();
                    producers.begin();
                    producers.awaitCalls();
                }
            } finally {
                assertSame(injected, assertThrows(IllegalStateException.class, producers::close).getCause());
            }
            assertTrue(uncaught.isEmpty(), "producer cleanup escaped the failure channel");
        } finally {
            adapter.set(null, original);
        }
    }
}
