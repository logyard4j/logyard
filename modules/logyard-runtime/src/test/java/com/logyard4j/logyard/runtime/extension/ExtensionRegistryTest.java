package com.logyard4j.logyard.runtime.extension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.formatting.TextFormatter;
import com.logyard4j.logyard.api.spi.formatting.TextFormatterProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ExtensionRegistryTest {
    @Test
    void snapshotsProviderMaps() {
        TextFormatterProvider formatter = new TestFormatterProvider();
        Map<String, TextFormatterProvider> mutable = new LinkedHashMap<>();
        mutable.put(formatter.name(), formatter);

        ExtensionRegistry registry = new ExtensionRegistry(mutable, Map.of(), Map.of(), Map.of());
        mutable.clear();

        assertSame(formatter, registry.formatters().get("test"));
        assertThrows(UnsupportedOperationException.class, () -> registry.formatters().clear());
    }

    private static final class TestFormatterProvider implements TextFormatterProvider {
        @Override public String name() { return "test"; }
        @Override public TextFormatter create(ProviderConfiguration configuration) { return event -> "test"; }
    }
}
