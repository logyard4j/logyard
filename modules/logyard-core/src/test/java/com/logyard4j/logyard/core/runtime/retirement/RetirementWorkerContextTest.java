package com.logyard4j.logyard.core.runtime.retirement;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RetirementWorkerContextTest {
    @Test
    void delayedRetirementDoesNotRetainTheRuntimeCreatorsRequestContext() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        InheritableThreadLocal<String> request = new InheritableThreadLocal<>() {
            @Override
            protected String childValue(String parent) {
                copies.incrementAndGet();
                return parent;
            }
        };
        ClassLoader expectedLoader = Thread.currentThread().getContextClassLoader();
        RetirementExecutor executor;
        request.set("runtime creation request");
        try {
            executor = new RetirementExecutor();
        } finally {
            request.remove();
        }

        CompletableFuture<String> observed = new CompletableFuture<>();
        CompletableFuture<ClassLoader> loader = new CompletableFuture<>();
        CompletableFuture<Thread> worker = new CompletableFuture<>();
        executor.scheduleFinal(() -> {
            observed.complete(request.get());
            loader.complete(Thread.currentThread().getContextClassLoader());
            worker.complete(Thread.currentThread());
        });
        Thread thread = worker.get(5, TimeUnit.SECONDS);
        thread.join(5_000);
        assertFalse(thread.isAlive());
        assertNull(observed.get(5, TimeUnit.SECONDS));
        assertEquals(0, copies.get());
        assertSame(expectedLoader, loader.get(5, TimeUnit.SECONDS));
    }
}
