package com.logyard4j.logyard.api.diagnostics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeHealthSnapshotTest {
    @Test
    void aggregateRejectsOversizedInputBeforeTraversingOrCopyingIt() {
        List<ComponentHealth> oversized = new AbstractList<>() {
            @Override
            public ComponentHealth get(int index) {
                throw new AssertionError("oversized health input must not be traversed");
            }

            @Override
            public int size() {
                return RuntimeHealth.MAX_COMPONENTS + 1;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> RuntimeHealth.from(oversized));
        assertThrows(IllegalArgumentException.class, () -> snapshot(HealthStatus.HEALTHY, true, oversized));
    }

    @Test
    void directConstructionCannotReportAHealthierStateThanItsComponents() {
        List<ComponentHealth> failed = List.of(component(HealthStatus.FAILED));
        assertThrows(IllegalArgumentException.class, () -> snapshot(HealthStatus.HEALTHY, true, failed));
        assertThrows(IllegalArgumentException.class, () -> snapshot(HealthStatus.DEGRADED, false, failed));
    }

    @Test
    void aReportCanConservativelyDescribeItsOwnUnavailableState() {
        RuntimeHealth failed = snapshot(HealthStatus.FAILED, false, List.of(component(HealthStatus.HEALTHY)));
        assertEquals(HealthStatus.FAILED, failed.status());
        assertFalse(failed.ready());
        assertFalse(snapshot(HealthStatus.HEALTHY, false, List.of()).ready());
        assertThrows(IllegalArgumentException.class, () -> snapshot(HealthStatus.STOPPING, true, List.of()));
    }

    @Test
    void snapshotsDetachInputAndAcceptTheExactComponentLimit() {
        List<ComponentHealth> components = new ArrayList<>(Collections.nCopies(
                RuntimeHealth.MAX_COMPONENTS, component(HealthStatus.HEALTHY)));
        RuntimeHealth aggregate = RuntimeHealth.from(components);
        RuntimeHealth direct = snapshot(HealthStatus.HEALTHY, true, components);
        components.clear();

        assertEquals(RuntimeHealth.MAX_COMPONENTS, aggregate.components().size());
        assertEquals(aggregate.components(), direct.components());
        assertThrows(UnsupportedOperationException.class, () -> aggregate.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> direct.components().clear());
    }

    @Test
    void nullCollectionsAndComponentsAreRejectedByBothEntryPoints() {
        assertThrows(NullPointerException.class, () -> RuntimeHealth.from(null));
        assertThrows(NullPointerException.class, () -> snapshot(HealthStatus.HEALTHY, true, null));
        List<ComponentHealth> invalid = Collections.singletonList(null);
        assertThrows(NullPointerException.class, () -> RuntimeHealth.from(invalid));
        assertThrows(NullPointerException.class, () -> snapshot(HealthStatus.HEALTHY, true, invalid));
    }

    private static RuntimeHealth snapshot(HealthStatus status, boolean ready, List<ComponentHealth> components) {
        return new RuntimeHealth(Instant.EPOCH, status, ready, components);
    }

    private static ComponentHealth component(HealthStatus status) {
        return new ComponentHealth("output", "output", status, Map.of(), Map.of());
    }
}
