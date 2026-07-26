package com.zsumz.logyard.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.spi.output.OutputProvider;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.loading.LogyardConfigLoader;
import com.zsumz.logyard.core.failure.ComponentInvocationException;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OutputAssemblerTest {
    @Test
    void reusesOutputsOnlyWhenTheirCompleteSignatureMatches() {
        LogyardConfig config = consoleConfig();
        RuntimeAssembly first = LogyardRuntimeFactory.assemble(config, null);
        RuntimeAssembly second = LogyardRuntimeFactory.assemble(config, first);

        try {
            assertSame(first.plan().outputs().get("console"), second.plan().outputs().get("console"));
        } finally {
            second.closeCandidateOutputs(first, new IllegalStateException("test cleanup"));
            first.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
        }
    }

    @Test
    void rejectsStructuralChangesToAnOpenFileOutput() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-locked-output-")) {
            Path path = temporary.path().resolve("application.jsonl");
            RuntimeAssembly current = LogyardRuntimeFactory.assemble(fileConfig(path, true), null);
            try {
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> LogyardRuntimeFactory.assemble(fileConfig(path, false), current));

                assertTrue(failure.getMessage().contains("at locked path"));
                assertTrue(failure.getMessage().contains("restart the process"));
            } finally {
                current.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));
            }
        }
    }

    @Test
    void laterProviderFailureDoesNotModifyPreparedFileOutput() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-provider-output-")) {
            Path path = temporary.path().resolve("application.jsonl");
            Files.writeString(path, "KEEP-ME\n", StandardCharsets.UTF_8);
            LogyardConfig config = fileThenFailingProviderConfig(path);
            OutputProvider failing = failingProvider();
            ExtensionRegistry extensions =
                    new ExtensionRegistry(Map.of(), Map.of(), Map.of("failing", failing), Map.of());

            assertEquals(List.of("json", "later"), List.copyOf(config.outputs().keySet()));
            assertThrows(ComponentInvocationException.class, () -> OutputAssembler.assemble(config, null, extensions));
            assertEquals("KEEP-ME\n", Files.readString(path, StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsDuplicateNormalizedFilePathsBeforeOpeningEitherOutput() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-duplicate-output-")) {
            Path path = temporary.path().resolve("events.jsonl");
            Path equivalent = temporary.path().resolve("nested/../events.jsonl");
            LogyardConfig config = duplicateFileConfig(path, equivalent);

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LogyardRuntimeFactory.assemble(config, null));

            assertTrue(failure.getMessage().contains("'audit'"));
            assertTrue(failure.getMessage().contains("'events'"));
            assertTrue(failure.getMessage().contains(path.toAbsolutePath().normalize().toString()));
            assertTrue(Files.notExists(path.resolveSibling(path.getFileName() + ".logyard.lock")));
        }
    }

    @Test
    void rejectsTheExactSameFilePathBeforeOpeningEitherOutput() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-exact-duplicate-output-")) {
            Path path = temporary.path().resolve("events.jsonl");

            assertThrows(
                    IllegalArgumentException.class,
                    () -> LogyardRuntimeFactory.assemble(duplicateFileConfig(path, path), null));

            assertTrue(Files.notExists(path));
            assertTrue(Files.notExists(path.resolveSibling(path.getFileName() + ".logyard.lock")));
        }
    }

    @Test
    void rejectsHardLinkAliasesBeforeOpeningEitherOutput() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-hard-link-output-")) {
            Path path = temporary.path().resolve("events.jsonl");
            Path alias = temporary.path().resolve("audit.jsonl");
            Files.writeString(path, "KEEP-ME\n", StandardCharsets.UTF_8);
            try {
                Files.createLink(alias, path);
            } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
                return;
            }

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LogyardRuntimeFactory.assemble(duplicateFileConfig(path, alias), null));

            assertTrue(failure.getMessage().contains("'audit'"));
            assertTrue(failure.getMessage().contains("'events'"));
            assertTrue(failure.getMessage().contains(path.toAbsolutePath().normalize().toString()));
            assertTrue(failure.getMessage().contains(alias.toAbsolutePath().normalize().toString()));
            assertEquals("KEEP-ME\n", Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(Files.notExists(path.resolveSibling(path.getFileName() + ".logyard.lock")));
            assertTrue(Files.notExists(alias.resolveSibling(alias.getFileName() + ".logyard.lock")));
        }
    }

    @Test
    void closingAnActivatedUnusedAsyncFileOutputPreservesExistingContents() throws Exception {
        try (TestDirectory temporary = TestDirectory.create("logyard-unused-async-output-")) {
            Path path = temporary.path().resolve("application.jsonl");
            Files.writeString(path, "KEEP-ME\n", StandardCharsets.UTF_8);
            RuntimeAssembly assembly = LogyardRuntimeFactory.assemble(asyncFileConfig(path, false), null);

            assembly.activateCandidateOutputs();
            assembly.closeCandidateOutputs(null, new IllegalStateException("test cleanup"));

            assertEquals("KEEP-ME\n", Files.readString(path, StandardCharsets.UTF_8));
        }
    }

    private static LogyardConfig consoleConfig() {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["console"] }
                [outputs.console]
                type = "console"
                color = { mode = "never", theme = "mono" }
                """;
        return LogyardConfigLoader.parse(text, "console-output.toml", Path.of("."), Map.of());
    }

    private static LogyardConfig fileConfig(Path path, boolean append) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = %s
                flush = "10ms"
                """.formatted(path, append);
        return LogyardConfigLoader.parse(text, "file-output.toml", Path.of("."), Map.of());
    }

    private static LogyardConfig asyncFileConfig(Path path, boolean append) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "async"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = %s
                flush = "10ms"
                """.formatted(path, append);
        return LogyardConfigLoader.parse(text, "async-file-output.toml", Path.of("."), Map.of());
    }

    private static LogyardConfig fileThenFailingProviderConfig(Path path) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "async"
                capacity = 16
                [loggers]
                root = { level = "info", outputs = ["json", "later"] }
                [outputs.json]
                type = "file"
                path = "%s"
                append = false
                flush = "10ms"
                [outputs.later]
                type = "custom"
                provider = "failing"
                """.formatted(path);
        return LogyardConfigLoader.parse(text, "candidate-output.toml", Path.of("."), Map.of());
    }

    private static LogyardConfig duplicateFileConfig(Path first, Path second) {
        String text = """
                schema = 1
                [service]
                name = "test"
                [delivery]
                mode = "sync"
                [loggers]
                root = { level = "info", outputs = ["audit", "events"] }
                [outputs.audit]
                type = "file"
                path = "%s"
                [outputs.events]
                type = "file"
                path = "%s"
                """.formatted(first, second);
        return LogyardConfigLoader.parse(text, "duplicate-output.toml", Path.of("."), Map.of());
    }

    private static OutputProvider failingProvider() {
        return new OutputProvider() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public com.zsumz.logyard.api.spi.output.EventSink create(
                    com.zsumz.logyard.api.spi.output.OutputProviderContext context,
                    com.zsumz.logyard.api.spi.config.ProviderConfiguration configuration) {
                throw new IllegalStateException("provider construction failed");
            }
        };
    }
}
