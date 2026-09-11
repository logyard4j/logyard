package com.logyard4j.slf4j;

import com.logyard4j.slf4j.internal.diagnostics.ProviderDiagnostics;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class Slf4jDiagnosticIsolationTest {
    @Test
    void mappingFailureCannotWaitForStalledStderrOnTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> writer = new AtomicReference<>();
        PrintStream previous = System.err;
        PrintStream stalled = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                writer.compareAndSet(null, Thread.currentThread());
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("diagnostic output was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        });
        Thread caller = new Thread(() -> {
            try {
                ProviderDiagnostics.eventMappingFailure("test.Service", new IllegalStateException("mapping failed"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "slf4j-diagnostic-test");
        caller.setDaemon(true);
        try {
            System.setErr(stalled);
            caller.start();
            caller.join(1_000);
            assertFalse(caller.isAlive(), "SLF4J diagnostic waited for stderr");
            assertNull(failure.get());
        } finally {
            release.countDown();
            caller.join(2_000);
            if (writer.get() != null) {
                writer.get().join(2_000);
            }
            System.setErr(previous);
            stalled.close();
        }
    }
}
