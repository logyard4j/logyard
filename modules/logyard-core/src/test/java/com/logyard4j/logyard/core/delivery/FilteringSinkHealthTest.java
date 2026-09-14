package com.logyard4j.logyard.core.delivery;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.diagnostics.ComponentHealth;
import com.logyard4j.logyard.api.diagnostics.HealthStatus;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.diagnostics.HealthContributor;
import com.logyard4j.logyard.api.spi.output.EventSink;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FilteringSinkHealthTest {
    @Test
    void aFullDelegateSnapshotRemainsAvailableWithoutLosingDetails() {
        ComponentHealth original = snapshot(details(ComponentHealth.MAX_ENTRIES));

        ComponentHealth filtered = filter(original);

        assertEquals(original, filtered);
        assertEquals(ComponentHealth.MAX_ENTRIES, filtered.details().size());
    }

    @Test
    void addsTheMinimumWhenOneDetailSlotRemains() {
        Map<String, String> details = details(ComponentHealth.MAX_ENTRIES - 1);
        ComponentHealth original = snapshot(details);

        ComponentHealth filtered = filter(original);

        details.put("minimum_level", "warn");
        assertEquals(snapshot(details), filtered);
        assertEquals(ComponentHealth.MAX_ENTRIES - 1, original.details().size());
    }

    @Test
    void replacesAnExistingMinimumAtTheDetailLimit() {
        Map<String, String> details = details(ComponentHealth.MAX_ENTRIES - 1);
        details.put("minimum_level", "debug");
        ComponentHealth original = snapshot(details);

        ComponentHealth filtered = filter(original);

        details.put("minimum_level", "warn");
        assertEquals(snapshot(details), filtered);
        assertEquals("debug", original.details().get("minimum_level"));
    }

    private static ComponentHealth filter(ComponentHealth original) {
        return new FilteringSink(Level.WARN, new ReportedSink(original)).health("output");
    }

    private static ComponentHealth snapshot(Map<String, String> details) {
        return new ComponentHealth("output", "test", HealthStatus.RECOVERING, details, Map.of("attempts", 7L));
    }

    private static Map<String, String> details(int count) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) values.put("detail." + index, "value-" + index);
        return values;
    }

    private record ReportedSink(ComponentHealth snapshot) implements EventSink, HealthContributor {
        @Override public void accept(LogEvent event) { }
        @Override public ComponentHealth health(String name) { return snapshot; }
    }
}
