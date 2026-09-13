package com.logyard4j.logyard.runtime.reload.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.reload.ReloadResult;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.logyard.runtime.assembly.LogyardRuntimeFactory;
import com.logyard4j.logyard.runtime.assembly.RuntimeAssembly;
import com.logyard4j.logyard.runtime.context.ContextPolicyRegistry;
import com.logyard4j.logyard.runtime.diagnostics.ReloadDiagnostics;
import com.logyard4j.logyard.runtime.reload.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ContextPolicyReloadTest {
    @Test
    void mdcPolicyChangeRequiresRestartAndLeavesConfigurationAndAdapterPolicyUnchanged() throws Exception {
        Path directory = Files.createTempDirectory("logyard-mdc-reload-");
        Path source = directory.resolve("logyard.toml");
        Path output = directory.resolve("events.jsonl");
        Files.writeString(source, config(output, "[]"), StandardCharsets.UTF_8);
        ConfigurationSnapshot snapshot = ConfigurationSnapshot.read(source);
        LogyardConfig config = snapshot.parse(Map.of());
        RuntimeAssembly assembly = LogyardRuntimeFactory.assemble(config, null);
        assembly.activateCandidateOutputs();
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(assembly.plan());
        LogyardRuntimeFactory.attach(runtime, assembly);
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        ReloadDiagnostics diagnostics = new ReloadDiagnostics() {
            @Override
            public void rejected(Path ignored, Throwable failure) {
                rejection.set(failure);
            }
        };
        ReloadCoordinator coordinator =
                new ReloadCoordinator(source, runtime, snapshot, assembly, diagnostics, Map.of());

        try {
            assertEquals(Set.of(), ContextPolicyRegistry.sourceFor(runtime).get().includedKeys());
            Files.writeString(source, config(output, "[\"request.id\"]"), StandardCharsets.UTF_8);

            assertEquals(ReloadResult.REJECTED, coordinator.reloadIfChanged());
            assertEquals(java.util.List.of(), coordinator.currentConfig().context().mdc());
            assertEquals(Set.of(), ContextPolicyRegistry.sourceFor(runtime).get().includedKeys());
            assertNotNull(rejection.get());
            assertTrue(rejection.get().getMessage().contains("context.mdc"));
        } finally {
            runtime.close();
        }
    }

    private static String config(Path output, String mdc) {
        return """
                schema = 1
                [service]
                name = "test"
                [runtime]
                shutdown_timeout = "2s"
                internal_status = "off"
                [delivery]
                mode = "sync"
                [context]
                mdc = %s
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = true
                flush = "0s"
                """.formatted(mdc, output.toString().replace("\\", "\\\\"));
    }
}
