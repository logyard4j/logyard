package com.logyard4j.logyard.runtime.reload.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReloadStateTest {
    @Test
    void admitsOneWriterAndRejectsAStaleGeneration() throws Exception {
        ConfigurationSnapshot firstSnapshot = snapshot("info");
        ConfigurationSnapshot secondSnapshot = snapshot("debug");
        RuntimeAssembly firstAssembly = assembly(firstSnapshot);
        RuntimeAssembly secondAssembly = assembly(secondSnapshot);
        ReloadState state = new ReloadState(firstSnapshot, firstAssembly);
        ReloadState.Reservation first = state.tryReserve();

        try {
            assertNotNull(first);
            assertNull(state.tryReserve());
            assertTrue(state.commit(first, secondSnapshot, secondAssembly));
            assertEquals(1L, state.current().generation());
            assertFalse(state.commit(first, firstSnapshot, firstAssembly));
        } finally {
            state.release(first);
            secondAssembly.closeCandidateOutputs(firstAssembly, new IllegalStateException("test cleanup"));
            firstAssembly.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    private static RuntimeAssembly assembly(ConfigurationSnapshot snapshot) {
        LogyardConfig config = snapshot.parse(Map.of());
        return LogyardRuntimeFactory.assemble(config, null);
    }

    private static ConfigurationSnapshot snapshot(String level) throws Exception {
        String text = """
                schema = 1
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never" }
                """.formatted(level);
        return ConfigurationSnapshot.capture(
                "reload-state.toml",
                Path.of("."),
                null,
                text.getBytes(StandardCharsets.UTF_8));
    }
}
