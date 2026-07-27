package com.zsumz.logyard.core.runtime.retirement;

import com.zsumz.logyard.core.routing.PlanEpoch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetirementSequenceTest {
    @Test
    void schedulesCleanupInPublicationOrderWhenANewerEpochDrainsFirst() {
        RetirementSequence sequence = new RetirementSequence();
        PlanEpoch olderEpoch = new PlanEpoch();
        PlanEpoch newerEpoch = new PlanEpoch();
        AtomicReference<Runnable> olderScheduled = new AtomicReference<>();
        AtomicReference<Runnable> newerScheduled = new AtomicReference<>();
        List<String> cleanupOrder = new ArrayList<>();
        assertTrue(olderEpoch.tryAcquire());

        CompletableFuture<Void> older = sequence.retire(
                olderEpoch,
                () -> cleanupOrder.add("older"),
                olderScheduled::set);
        CompletableFuture<Void> newer = sequence.retire(
                newerEpoch,
                () -> cleanupOrder.add("newer"),
                newerScheduled::set);

        assertNull(olderScheduled.get());
        assertNull(newerScheduled.get());
        assertFalse(older.isDone());
        assertFalse(newer.isDone());

        olderEpoch.release();
        assertNotNull(olderScheduled.get());
        assertNull(newerScheduled.get());

        olderScheduled.get().run();
        assertTrue(older.isDone());
        assertNotNull(newerScheduled.get());

        newerScheduled.get().run();
        assertTrue(newer.isDone());
        assertEquals(List.of("older", "newer"), cleanupOrder);
    }
}
