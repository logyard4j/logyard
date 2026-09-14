package com.logyard4j.logyard.output.json.file.rotation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ArchiveWorkerContextTest {
    @Test
    void archiveWorkerDoesNotRetainTheRequestThatFirstOpenedTheFile() throws Exception {
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
        Path output = Path.of("events.jsonl");
        ArchiveMaintenanceWorker worker;
        request.set("file opening request");
        try {
            worker = new ArchiveMaintenanceWorker(output, () -> {
                observed.complete(request.get());
                loader.complete(Thread.currentThread().getContextClassLoader());
            });
        } finally {
            request.remove();
        }
        worker.start();
        worker.await(Duration.ofSeconds(5), output);
        assertFalse(worker.alive());
        assertNull(observed.get(5, TimeUnit.SECONDS));
        assertEquals(0, copies.get());
        assertSame(expectedLoader, loader.get(5, TimeUnit.SECONDS));
    }
}
