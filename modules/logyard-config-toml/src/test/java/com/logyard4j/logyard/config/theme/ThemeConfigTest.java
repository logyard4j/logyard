package com.logyard4j.logyard.config.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class ThemeConfigTest {
    @Test
    void acceptsOrdinaryEmptyMapsAndCopiesThemImmutably() {
        ThemeConfig config = new ThemeConfig("custom", Map.of(), Map.of());

        assertEquals(Map.of(), config.roles());
        assertEquals(Map.of(), config.levels());
        assertThrows(UnsupportedOperationException.class, () -> config.levels().clear());
    }
}
