package com.zsumz.logyard.runtime.assembly.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.context.ContextProvider;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;

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
