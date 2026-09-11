package com.logyard4j.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.LogyardRuntime;
import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.diagnostics.EffectiveRoute;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.core.runtime.DefaultLogyardRuntime;
import com.logyard4j.runtime.context.ContextPolicyRegistry;
import com.logyard4j.runtime.context.ContextPolicySnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class LogyardRuntimeFactoryTest {
    @Test
    void assemblesRoutesAndBuiltInProcessors() {
        String text = """
                schema = 1
                [service]
                name = "test"
                [runtime]
                shutdown_timeout = "1s"
                [context]
                redact = ["*.token"]
                [delivery]
                mode = "sync"
                capacity = 16
                [filters.sample]
                type = "sampling"
                probability = 1.0
                key = "logger"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                "com.example" = { level = "debug", filters = ["sample"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        LogyardConfig config = LogyardConfigLoader.parse(text, "test.toml", Path.of("."), Map.of());
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            EffectiveRoute route = runtime.explain("com.example.Worker");
            assertEquals("com.example", route.matchedRule());
            assertEquals(List.of("sample", "logyard-redaction"), route.processors());
        }
    }

    @Test
    void createsBoundedAsyncDeliveryWhenConfigured() {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "async"
                capacity = 32
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        LogyardConfig config = LogyardConfigLoader.parse(text, "async.toml", Path.of("."), Map.of());
        try (LogyardRuntime runtime = LogyardRuntimeFactory.create(config)) {
            runtime.logger("com.example.Worker").atInfo().log("hello");
            runtime.flush();
            ComponentHealth console = runtime.health().components().stream()
                    .filter(component -> "console".equals(component.name()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("async", console.details().get("delivery"));
            assertEquals("true", console.details().get("caller_thread_delivery"));
            assertTrue(runtime.health().ready());
        }
    }

    @Test
    void publishesContextPolicyUpdatesThroughAStableVolatileSource() {
        LogyardConfig initialConfig = configWithMdc("request.id");
        LogyardConfig nextConfig = configWithMdc("tenant.id");
        RuntimeAssembly initial = LogyardRuntimeFactory.assemble(initialConfig, null);
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(initial.plan());
        LogyardRuntimeFactory.attach(runtime, initial);
        Supplier<ContextPolicySnapshot> source = ContextPolicyRegistry.sourceFor(runtime);
        try {
            assertEquals(List.of("request.id"), List.copyOf(source.get().includedKeys()));

            RuntimeAssembly next = LogyardRuntimeFactory.assemble(nextConfig, initial);
            runtime.reload(next.plan());
            LogyardRuntimeFactory.attach(runtime, next);

            assertEquals(List.of("tenant.id"), List.copyOf(source.get().includedKeys()));
        } finally {
            runtime.close();
        }
    }

    private static LogyardConfig configWithMdc(String key) {
        return LogyardConfigLoader.parse(
                """
                        schema = 1
                        [context]
                        mdc = ["%s"]
                        [delivery]
                        mode = "sync"
                        capacity = 16
                        [loggers]
                        root = { level = "info", outputs = ["console"] }
                        [outputs.console]
                        type = "console"
                        color = { mode = "never", theme = "mono" }
                        """.formatted(key),
                "context-policy.toml",
                Path.of("."),
                Map.of());
    }
}
