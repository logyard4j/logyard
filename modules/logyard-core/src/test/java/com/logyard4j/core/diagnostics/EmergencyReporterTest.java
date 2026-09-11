package com.logyard4j.core.diagnostics;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmergencyReporterTest {
    @Test
    void stalledStderrRetainsOneDaemonAndSuppressesFurtherDiagnostics() throws Exception {
        EmergencyReporter reporter = new EmergencyReporter(Duration.ZERO);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> writer = new AtomicReference<>();
        PrintStream previous = System.err;
        PrintStream stalled = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                writer.compareAndSet(null, Thread.currentThread());
                entered.countDown();
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
        try {
            System.setErr(stalled);
            reporter.report("first failure");
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 1_000; index++) {
                reporter.report("another failure");
            }
            assertEquals(1_000L, reporter.suppressedReports());
            assertTrue(writer.get().isDaemon());
            assertNull(writer.get().getContextClassLoader());
        } finally {
            release.countDown();
            if (writer.get() != null) {
                writer.get().join(2_000);
            }
            System.setErr(previous);
            stalled.close();
        }
    }
}
