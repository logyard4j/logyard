package com.logyard4j.logyard.runtime.installation.process;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShutdownHookContextTest {
    @Test
    void registeredHookRetainsNoRequestLocalsForTheRemainingProcessLifetime() throws Exception {
        AtomicInteger copies = new AtomicInteger();
        InheritableThreadLocal<String> request = new InheritableThreadLocal<>() {
            @Override
            protected String childValue(String parent) {
                copies.incrementAndGet();
                return parent;
            }
        };
        List<Thread> registered = new ArrayList<>();
        InstallationShutdownHook registrar = new InstallationShutdownHook(registered::add);
        CompletableFuture<String> observed = new CompletableFuture<>();
        CompletableFuture<ClassLoader> loader = new CompletableFuture<>();
        ClassLoader expectedLoader = Thread.currentThread().getContextClassLoader();
        request.set("installation request");
        try {
            assertTrue(registrar.install(() -> {
                observed.complete(request.get());
                loader.complete(Thread.currentThread().getContextClassLoader());
            }));
            assertTrue(registrar.install(() -> { throw new AssertionError("duplicate hook"); }));
        } finally {
            request.remove();
        }
        assertEquals(1, registered.size());
        Thread hook = registered.getFirst();
        hook.start();
        hook.join(5_000);
        assertFalse(hook.isAlive());
        assertNull(observed.get(5, TimeUnit.SECONDS));
        assertEquals(0, copies.get());
        assertSame(expectedLoader, loader.get(5, TimeUnit.SECONDS));
    }
}
