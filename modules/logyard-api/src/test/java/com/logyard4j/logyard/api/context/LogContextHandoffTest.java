package com.logyard4j.logyard.api.context;

import com.logyard4j.logyard.api.event.AttributeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies that wrapped tasks reinstate a captured context and release it on the executing thread. */
final class LogContextHandoffTest {
    @AfterEach
    void contextIsUnboundAfterEveryTest() {
        assertTrue(LogContext.current().isEmpty(), "a test must not leak scoped context onto the runner thread");
    }

    @Test
    void wrappedRunnableReinstatesAndRestoresAcrossAThreadHandoff() throws Exception {
        Object[] observed = new Object[1];
        Runnable body = () -> observed[0] = LogContext.current().get("request.id");
        Runnable task;
        ContextScope scope = LogContext.push("request.id", "req-1");
        try (scope) {
            task = LogContext.wrap(body);
        }
        assertTrue(LogContext.current().isEmpty(), "wrapping must not extend the scope it captured");

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ContextScope worker = executor.submit(() -> LogContext.push("request.id", "worker")).get();
            executor.submit(task).get();
            assertEquals("req-1", observed[0], "the wrapped task runs with the captured context");
            assertEquals(
                    "worker",
                    executor.submit(() -> LogContext.current().get("request.id")).get(),
                    "the executing thread's own context is restored afterwards");
            executor.submit(worker::close).get();
            assertNull(executor.submit(() -> LogContext.current().get("request.id")).get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void wrappedCallableReturnsItsValueUnderTheCapturedContext() throws Exception {
        Callable<String> body = () -> LogContext.current().get("tenant") + "/" + LogContext.current().get("request.id");
        Callable<String> task;
        ContextScope scope = LogContext.push(AttributeSet.builder(2)
                .put("tenant", "north")
                .put("request.id", "req-2")
                .build());
        try (scope) {
            task = LogContext.wrap(body);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals("north/req-2", executor.submit(task).get());
            assertNull(
                    executor.submit(() -> LogContext.current().get("tenant")).get(),
                    "an unbound worker thread must be unbound again after the task");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void failingTaskCannotLeakUnclosedScopesOrClobberWorkerContextOnLaterClose() {
        ContextScope[] leaked = new ContextScope[1];
        Runnable task = LogContext.wrap((Runnable) () -> {
            assertTrue(LogContext.current().isEmpty(), "an empty captured context replaces worker context");
            leaked[0] = LogContext.push("tenant", "leaked");
            throw new IllegalStateException("boom");
        });
        ContextScope worker = LogContext.push("tenant", "worker");
        try (worker) {
            assertThrows(IllegalStateException.class, task::run);
            assertEquals("worker", LogContext.current().get("tenant"));
            leaked[0].close();
            assertEquals("worker", LogContext.current().get("tenant"));
        }
    }

    @Test
    void aFailingWrappedTaskStillRestoresTheExecutingThreadContext() throws Exception {
        Runnable body = () -> {
            throw new IllegalStateException("boom");
        };
        Runnable failing;
        ContextScope scope = LogContext.push("request.id", "req-3");
        try (scope) {
            failing = LogContext.wrap(body);
        }

        boolean[] failed = new boolean[1];
        AttributeSet[] afterFailure = new AttributeSet[1];
        Thread worker = new Thread(() -> {
            try {
                failing.run();
            } catch (IllegalStateException expected) {
                failed[0] = true;
            }
            afterFailure[0] = LogContext.current();
        });
        worker.start();
        worker.join();

        assertTrue(failed[0], "the wrapper must not swallow the task failure");
        assertTrue(afterFailure[0].isEmpty(), "a failed task must not leave context behind");
    }
}
