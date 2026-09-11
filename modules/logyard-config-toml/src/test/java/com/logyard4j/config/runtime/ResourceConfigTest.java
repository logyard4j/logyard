package com.logyard4j.config.runtime;

import com.logyard4j.config.loading.LogyardConfigLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceConfigTest {
    @Test
    void parsesCanonicalSelectionsAndGivesExclusionsPrecedence() {
        ResourceConfig resource = LogyardConfigLoader.parse("""
                schema = 1
                [resource]
                include = ["service.name", "service.version", "region"]
                exclude = ["service.version"]
                [resource.attributes]
                region = "eu-west"
                [outputs.console]
                type = "console"
                """, "resource.toml", Path.of("."), Map.of()).resource();
        assertTrue(resource.includes("service.name"));
        assertTrue(resource.includes("region"));
        assertFalse(resource.includes("service.version"));
        assertFalse(resource.includes("service.instance.id"));
        assertTrue(new ResourceConfig(Map.of()).includes("service.name"));
    }

    @Test
    void rejectsUnboundedAmbiguousOrNonliteralSelections() {
        for (List<String> invalid : List.of(List.of("*"), List.of("service.*"), List.of(""),
                List.of("a", "a"), List.of("x".repeat(129)), Collections.nCopies(65, "a"))) {
            assertThrows(IllegalArgumentException.class, () -> new ResourceConfig(Map.of(), invalid, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new ResourceConfig(Map.of(), List.of(), invalid));
        }
    }
}
