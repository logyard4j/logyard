package com.logyard4j.logyard.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.logyard4j.logyard.api.Level;
import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.Test;

final class OtelSeverityMappingTest {
    @Test
    void mapsEveryLevelOntoTheSeverityCarryingItsOwnSeverityNumber() {
        for (Level level : Level.values()) {
            Severity severity = OtelSeverity.of(level);
            assertEquals(level.severityNumber(), severity.getSeverityNumber(), level.name());
        }
    }

    @Test
    void mapsEachLevelOntoItsNamedSeverity() {
        assertEquals(Severity.TRACE, OtelSeverity.of(Level.TRACE));
        assertEquals(Severity.DEBUG, OtelSeverity.of(Level.DEBUG));
        assertEquals(Severity.INFO, OtelSeverity.of(Level.INFO));
        assertEquals(Severity.WARN, OtelSeverity.of(Level.WARN));
        assertEquals(Severity.ERROR, OtelSeverity.of(Level.ERROR));
    }

    @Test
    void coversTheCompleteLevelEnumeration() {
        assertEquals(5, Level.values().length);
    }
}
