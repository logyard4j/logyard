package com.logyard4j.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EmergencyTextTest {
    @Test
    void escapesLineSeparatorsTerminalEscapesAndDirectionalControls() {
        String identity = "test\r\n\u001b[2J\u0085\u2028\u2029\u061c\u200e\u200f\u202a\u202e\u2066\u2069";

        String sanitized = EmergencyText.sanitize(identity, 256);

        assertEquals("test\\r\\n\\u001b[2J\\u0085\\u2028\\u2029\\u061c\\u200e\\u200f"
                + "\\u202a\\u202e\\u2066\\u2069", sanitized);
    }

    @Test
    void truncatesLongIdentitiesWithoutSplittingSupplementaryCharacters() {
        for (int limit = 1; limit <= 64; limit++) {
            String sanitized = EmergencyText.sanitize("𐐀.\u2028".repeat(128), limit);
            assertTrue(sanitized.length() <= limit);
            assertTrue(sanitized.endsWith("…"));
            assertFalse(sanitized.contains("\u2028"));
            for (int index = 0; index < sanitized.length(); index++) {
                char character = sanitized.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    assertTrue(index + 1 < sanitized.length() && Character.isLowSurrogate(sanitized.charAt(++index)));
                } else {
                    assertFalse(Character.isLowSurrogate(character));
                }
            }
        }
    }
}
