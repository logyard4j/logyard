package com.logyard4j.logyard.core.delivery.async;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncWorkerContextTest {
    @Test
    void workerDoesNotCopyCallerThreadLocalsOrInvokeTheirChildCallbacks() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        InheritableThreadLocal<String> request = new InheritableThreadLocal<>() {
            @Override
            protected String childValue(String parent) {
                copies.incrementAndGet();
                return parent;
            }
        };
        CompletableFuture<String> observed = new CompletableFuture<>();
        CompletableFuture<ClassLoader> loader = new CompletableFuture<>();
        ClassLoader expectedLoader = Thread.currentThread().getContextClassLoader();
        AsyncWorkerThread worker;
        request.set("application request");
        try {
            worker = new AsyncWorkerThread("isolated-worker", () -> {
                observed.complete(request.get());
                loader.complete(Thread.currentThread().getContextClassLoader());
            });
        } finally {
            request.remove();
        }

        worker.start();
        try {
            assertNull(observed.get(5, TimeUnit.SECONDS));
            assertEquals(0, copies.get());
            assertSame(expectedLoader, loader.get(5, TimeUnit.SECONDS));
        } finally {
            assertTrue(worker.await(Duration.ofSeconds(5)));
        }
    }
}
