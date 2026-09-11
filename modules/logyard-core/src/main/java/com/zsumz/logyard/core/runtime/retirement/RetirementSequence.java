package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.diagnostics.EmergencyText;
import com.zsumz.logyard.core.diagnostics.EmergencyReporter;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.core.routing.PlanEpoch;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Preserves plan-publication order when epochs become eligible for cleanup out of order.
 *
 * <p>An output may be reused by several consecutive plans. Closing a newer plan before every
 * older plan has drained would then close the shared output beneath an older publisher or flush.
 * This sequence delays each eligible cleanup until its predecessor has completed without blocking
 * either publishers or the bounded retirement worker.</p>
 */
final class RetirementSequence {
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    synchronized CompletableFuture<Void> retire(
            PlanEpoch epoch,
            Runnable cleanup,
            Consumer<Runnable> scheduler) {
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(scheduler, "scheduler");

        CompletableFuture<Void> predecessor = tail;
        CompletableFuture<Void> retirement = epoch.retire(
                cleanup,
                task -> predecessor.whenComplete((ignored, failure) -> schedule(scheduler, task)));
        tail = retirement;
        return retirement;
    }

    private static void schedule(Consumer<Runnable> scheduler, Runnable task) {
        boolean scheduled = ComponentInvocationBoundary.invoke(
                "ordered runtime retirement scheduler",
                () -> scheduler.accept(task),
                (component, failure) -> EmergencyReporter.STDERR.report(
                        "Logyard failed to schedule ordered retirement: "
                                + EmergencyText.failureSummary(failure, 4_096)));
        if (!scheduled) {
            task.run();
        }
    }
}
