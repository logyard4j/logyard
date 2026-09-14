package com.logyard4j.logyard.api.context;

import com.logyard4j.logyard.api.event.AttributeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LogContextExecutorTest {
    @AfterEach
    void callerContextIsRestored() {
        assertTrue(LogContext.current().isEmpty());
    }

    @Test
    void eachSubmissionCapturesItsOwnContextAndReplacesTheWorkerContext() {
        ArrayDeque<Runnable> pending = new ArrayDeque<>();
        List<AttributeSet> observed = new ArrayList<>();
        Executor executor;
        try (ContextScope ignored = LogContext.push("construction", true)) {
            executor = LogContext.wrap((Executor) pending::addLast);
        }
        for (String request : List.of("first", "second")) {
            try (ContextScope ignored = LogContext.push("request", request)) {
                executor.execute(() -> observed.add(LogContext.current()));
            }
        }
        executor.execute(() -> observed.add(LogContext.current()));

        try (ContextScope ignored = LogContext.push("worker", true)) {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
                assertEquals(true, LogContext.current().get("worker"));
            }
        }
        assertEquals("first", observed.get(0).get("request"));
        assertEquals("second", observed.get(1).get("request"));
        assertTrue(observed.get(2).isEmpty());
        for (AttributeSet attributes : observed) {
            assertFalse(attributes.containsKey("construction"));
            assertFalse(attributes.containsKey("worker"));
        }
    }

    @Test
    void completableFutureUsesSubmissionContextWithoutTakingExecutorOwnership() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            Executor executor = LogContext.wrap(worker);
            CompletableFuture<Object> task;
            try (ContextScope ignored = LogContext.push("request", "future")) {
                task = CompletableFuture.supplyAsync(() -> LogContext.current().get("request"), executor);
            }
            assertEquals("future", task.get(5, TimeUnit.SECONDS));
            assertTrue(worker.submit(() -> LogContext.current().isEmpty()).get(5, TimeUnit.SECONDS));
            assertFalse(worker.isShutdown());
        }
    }

    @Test
    void inlineFailureRestoresTheCallerEvenWhenTheTaskLeavesAScopeOpen() {
        Executor executor = LogContext.wrap((Executor) Runnable::run);
        ContextScope[] leaked = new ContextScope[1];
        IllegalStateException failure = new IllegalStateException("task failed");
        try (ContextScope ignored = LogContext.push("request", "caller")) {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> executor.execute(() -> {
                leaked[0] = LogContext.push("request", "leaked");
                throw failure;
            })));
            assertEquals("caller", LogContext.current().get("request"));
            leaked[0].close();
            assertEquals("caller", LogContext.current().get("request"));
        }
    }

    @Test
    void rejectionAndNullValidationDoNotInstallContextOrRunTheTask() {
        RejectedExecutionException failure = new RejectedExecutionException("rejected");
        Executor executor = LogContext.wrap((Executor) task -> { throw failure; });
        try (ContextScope ignored = LogContext.push("request", "caller")) {
            assertSame(failure, assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
                throw new AssertionError("a rejected task must not run");
            })));
            assertEquals("caller", LogContext.current().get("request"));
            assertThrows(NullPointerException.class, () -> executor.execute(null));
        }
        assertThrows(NullPointerException.class, () -> LogContext.wrap((Executor) null));
    }
}
