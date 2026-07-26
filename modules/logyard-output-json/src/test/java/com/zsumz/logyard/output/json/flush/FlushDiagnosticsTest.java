package com.zsumz.logyard.output.json.flush;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlushDiagnosticsTest {
    @Test
    void repeatedDispatchFailuresAreRateLimited() {
        FlushDiagnostics.StderrFlushDiagnostics diagnostics = new FlushDiagnostics.StderrFlushDiagnostics();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            diagnostics.report(new IllegalStateException("first"));
            await(() -> !diagnostics.reportIsInFlight());
            diagnostics.report(new IllegalStateException("second"));
        } finally {
            System.setErr(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1L, output.lines().count());
        assertTrue(output.contains("first"));
        assertEquals(1L, diagnostics.suppressedReports());
    }

    @Test
    void reporterIsAnIsolatedDaemonPlatformThread() throws Exception {
        FlushDiagnostics.StderrFlushDiagnostics diagnostics = new FlushDiagnostics.StderrFlushDiagnostics();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        ObservedFailure failure = new ObservedFailure(inherited);
        PrintStream originalError = System.err;
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        inherited.set("caller-state");
        Thread.currentThread().setContextClassLoader(new ClassLoader(originalLoader) { });
        try {
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            diagnostics.report(failure);
            assertTrue(failure.awaitMessage());
            await(() -> !diagnostics.reportIsInFlight());
        } finally {
            System.setErr(originalError);
            Thread.currentThread().setContextClassLoader(originalLoader);
            inherited.remove();
        }

        assertNull(failure.inheritedValue.get());
        assertNull(failure.contextLoader.get());
        assertTrue(failure.daemon.get());
        assertFalse(failure.virtual.get());
    }

    @Test
    void blockedReportsCannotOccupyBothSharedDeadlineThreads() throws Exception {
        FlushDiagnostics.StderrFlushDiagnostics diagnostics = new FlushDiagnostics.StderrFlushDiagnostics(0L);
        BlockingPrintStream blockedError = new BlockingPrintStream();
        PrintStream original = System.err;
        CountDownLatch secondReturned = new CountDownLatch(1);
        CountDownLatch deadlineProbe = new CountDownLatch(1);
        try {
            System.setErr(blockedError);
            FlushScheduler.shared().schedule(Duration.ZERO, () -> diagnostics.report(new IllegalStateException("first")));
            assertTrue(blockedError.awaitBlocked());
            FlushScheduler.shared().schedule(Duration.ZERO, () -> {
                diagnostics.report(new IllegalStateException("second"));
                secondReturned.countDown();
            });
            assertTrue(secondReturned.await(2L, TimeUnit.SECONDS));
            FlushScheduler.shared().schedule(Duration.ZERO, deadlineProbe::countDown);
            assertTrue(deadlineProbe.await(2L, TimeUnit.SECONDS));
            assertEquals(1L, diagnostics.suppressedReports());
        } finally {
            blockedError.release();
            await(() -> !diagnostics.reportIsInFlight());
            System.setErr(original);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied before the deadline");
            }
            Thread.onSpinWait();
        }
    }

    @SuppressWarnings("serial")
    private static final class ObservedFailure extends IllegalStateException {
        private final InheritableThreadLocal<String> inherited;
        private final CountDownLatch messageBuilt = new CountDownLatch(1);
        private final AtomicReference<String> inheritedValue = new AtomicReference<>();
        private final AtomicReference<ClassLoader> contextLoader = new AtomicReference<>();
        private final AtomicBoolean daemon = new AtomicBoolean();
        private final AtomicBoolean virtual = new AtomicBoolean(true);

        private ObservedFailure(InheritableThreadLocal<String> inherited) {
            this.inherited = inherited;
        }

        @Override
        public String getMessage() {
            inheritedValue.set(inherited.get());
            contextLoader.set(Thread.currentThread().getContextClassLoader());
            daemon.set(Thread.currentThread().isDaemon());
            virtual.set(Thread.currentThread().isVirtual());
            messageBuilt.countDown();
            return "observed";
        }

        private boolean awaitMessage() throws InterruptedException {
            return messageBuilt.await(2L, TimeUnit.SECONDS);
        }
    }
}
