package com.logyard4j.logyard.core.diagnostics;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmergencyTextBoundsTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 6, 7, 16})
    void boundsFallbackTextToTheRequestedMaximum(int maximum) {
        assertEquals(EmergencyText.sanitize("null", maximum), EmergencyText.sanitize(null, maximum));
        assertEquals(EmergencyText.sanitize("null", maximum), EmergencyText.failureSummary(null, maximum));
        for (String value : new String[] {null, "", " \t\n"}) {
            String component = EmergencyText.threadComponent(value, maximum);
            assertTrue(!component.isEmpty() && component.length() <= maximum);
            assertTrue("unnamed".startsWith(component));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidLimitsBeforeInvokingFailureAccessors(int maximum) {
        var failure = new RuntimeException() {
            @Override
            public String getMessage() {
                throw new AssertionError("invalid limit invoked caller code");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> EmergencyText.sanitize(null, maximum));
        assertThrows(IllegalArgumentException.class, () -> EmergencyText.failureSummary(null, maximum));
        assertThrows(IllegalArgumentException.class, () -> EmergencyText.failureSummary(failure, maximum));
        assertThrows(IllegalArgumentException.class, () -> EmergencyText.threadComponent(null, maximum));
    }
}
