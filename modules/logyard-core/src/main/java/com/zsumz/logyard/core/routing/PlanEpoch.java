package com.zsumz.logyard.core.routing;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventSink;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Lock-free publication lease protecting outputs during bounded asynchronous retirement. */
public final class PlanEpoch {
    private static final int RETIRED = 1 << 31;
    private static final int COUNT_MASK = ~RETIRED;

    private final AtomicInteger state = new AtomicInteger();
    private final AtomicReference<Runnable> cleanup = new AtomicReference<>();
    private final AtomicReference<Consumer<Runnable>> scheduler = new AtomicReference<>();
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public boolean tryAcquire() {
        while (true) {
            int current = state.get();
            if ((current & RETIRED) != 0) {
                return false;
            }
            if ((current & COUNT_MASK) == COUNT_MASK) {
                throw new IllegalStateException("too many concurrent Logyard publishers");
            }
            if (state.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        int next = state.decrementAndGet();
        if (next == RETIRED) {
            scheduleCleanup();
        }
    }

    /** Whether publication has retired this epoch and rejected new leases. */
    public boolean retiring() {
        return (state.get() & RETIRED) != 0;
    }

    public CompletableFuture<Void> retire(Runnable action, Consumer<Runnable> cleanupScheduler) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(cleanupScheduler, "cleanupScheduler");
        if (!cleanup.compareAndSet(null, action)) {
            return completion;
        }
        scheduler.set(cleanupScheduler);
        int previous = state.getAndUpdate(current -> current | RETIRED);
        if ((previous & COUNT_MASK) == 0) {
            scheduleCleanup();
        }
        return completion;
    }

    private void scheduleCleanup() {
        if (!cleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.get().accept(this::runCleanup);
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
    }

    private void runCleanup() {
        try {
            cleanup.get().run();
            completion.complete(null);
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        } catch (Error failure) {
            completion.completeExceptionally(failure);
            throw failure;
        }
    }
}
