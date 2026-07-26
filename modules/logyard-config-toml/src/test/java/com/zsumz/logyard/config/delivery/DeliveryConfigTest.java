package com.zsumz.logyard.config.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class DeliveryConfigTest {
    @Test
    void acceptsAnOrdinaryEmptyMapAndCopiesItImmutably() {
        DeliveryConfig config = new DeliveryConfig("sync", DeliveryConfig.MIN_CAPACITY, Map.of());

        assertEquals(Map.of(), config.overflow());
        assertThrows(UnsupportedOperationException.class, () -> config.overflow().clear());
    }
}
