package com.logyard4j.runtime.assembly.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.spi.config.ProviderConfiguration;
import com.logyard4j.api.spi.context.ContextProvider;
import com.logyard4j.api.spi.processing.EventProcessor;
import com.logyard4j.api.spi.processing.EventProcessorKind;
import com.logyard4j.api.spi.processing.EventProcessorProvider;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.core.failure.ComponentInvocationException;
import com.logyard4j.runtime.extension.ExtensionRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProcessorAssemblerTest {
    private static final ExtensionRegistry NO_EXTENSIONS = new ExtensionRegistry(Map.of(), Map.of(), Map.of(), Map.of());

    @Test
    void assemblesOnlyReferencedProcessorsAndPreservesSemanticRouteOrder() {
        LogyardConfig config = config();
        ContextProvider contextProvider = new ContextProvider() {
            @Override public String name() { return "test-context"; }
            @Override public AttributeSet capture(List<String> includedKeys) { return AttributeSet.EMPTY; }
        };

        ProcessorAssembler.Assembly assembly = ProcessorAssembler.assemble(config, NO_EXTENSIONS, List.of(contextProvider));
        List<String> route = ProcessorAssembler.processorNames(config.rootLogger(), assembly.contextEnabled(), assembly.redactionEnabled());

        assertEquals(List.of("sample", "logyard-context", "logyard-redaction"), List.copyOf(assembly.processors().keySet()));
        assertEquals(List.of("logyard-context", "sample", "logyard-redaction"), route);
        assertTrue(assembly.contextEnabled());
        assertTrue(assembly.redactionEnabled());
    }

    @Test
    void convertsRecoverableProviderCreationErrorsAndPreservesTheCause() {
        LogyardConfig config = LogyardConfigLoader.parse(
                """
                schema = 1
                [delivery]
                mode = "sync"
                [filters.hostile]
                type = "custom"
                provider = "hostile"
                [loggers]
                root = { level = "info", outputs = ["console"], filters = ["hostile"] }
                [outputs.console]
                type = "console"
                """,
                "hostile-provider.toml",
                Path.of("."),
                Map.of());
        AssertionError providerFailure = new AssertionError("provider failed");
        EventProcessorProvider provider = new EventProcessorProvider() {
            @Override public String name() { return "hostile"; }
            @Override public EventProcessorKind kind() { return EventProcessorKind.FILTER; }
            @Override public EventProcessor create(ProviderConfiguration configuration) { throw providerFailure; }
        };
        ExtensionRegistry extensions = new ExtensionRegistry(Map.of(), Map.of(), Map.of(), Map.of("hostile", provider));

        ComponentInvocationException failure = assertThrows(
                ComponentInvocationException.class,
                () -> ProcessorAssembler.assemble(config, extensions, List.of()));

        assertEquals(providerFailure, failure.getCause());
    }

    private static LogyardConfig config() {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [context]
                redact = ["*.token"]
                [filters.sample]
                type = "sampling"
                probability = 1.0
                key = "logger"
                [loggers]
                root = { level = "info", outputs = ["console"], filters = ["sample"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        return LogyardConfigLoader.parse(text, "processor-assembler.toml", Path.of("."), Map.of());
    }
}
