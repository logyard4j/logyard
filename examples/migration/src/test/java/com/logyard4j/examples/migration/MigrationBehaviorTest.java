package com.logyard4j.examples.migration;

import com.logyard4j.runtime.tools.LogyardConfigTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.File;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compares emitted records from the real source providers with the packaged Logyard runtime. */
final class MigrationBehaviorTest {
    @TempDir Path directory;

    @Test
    void omittedMessagesStayOmittedAgainstBothSourceProviders() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            String xml = MigrationFixtures.single(provider, "%level APPROVED%n", "");
            Conversion converted = convert(provider, xml, true);
            assertEquals(0, converted.status(), converted.err());
            var original = SourceLogging.source(provider, xml);
            assertEquals(List.of("INFO APPROVED", "WARN APPROVED"), original.out());
            assertEquals(original, SourceLogging.migrated(converted.toml()));
        }
    }

    @Test
    void omittedAndExplicitConsoleTargetsPreserveFilteringAndCounts() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            for (String target : List.of("", "stdout", "stderr")) {
                String xml = MigrationFixtures.single(provider, "APPROVED %level %msg%n", target);
                Conversion converted = convert(provider, xml, true);
                assertEquals(0, converted.status(), converted.err());
                var original = SourceLogging.source(provider, xml);
                assertEquals(2, original.out().size() + original.err().size());
                assertEquals(target.equals("stderr") ? 2 : 0, original.err().size());
                assertEquals(original, SourceLogging.migrated(converted.toml()));
            }
        }
    }

    @Test
    void caseDistinctAppenderNamesPreserveBothDestinationsAndTheirThresholds() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            for (boolean async : List.of(false, true)) {
                String xml = MigrationFixtures.distinct(provider, async);
                Conversion converted = convert(provider, xml, !async);
                assertEquals(0, converted.status(), converted.err());
                var original = SourceLogging.source(provider, xml);
                assertEquals(2, original.out().size(), original.toString());
                assertEquals(1, original.err().size(), original.toString());
                assertEquals(original, SourceLogging.migrated(converted.toml()));
                if (async) assertEquals(3, convert(provider, xml, true).status());
            }
        }
    }

    @Test
    void contextLayoutsRequireManualConversionInsteadOfBroadeningCapturedData() throws Exception {
        for (String provider : List.of("logback", "log4j2")) {
            for (String context : List.of("%X{request.id}", "%X{missing}", "%X{missing:-fallback}", "%X",
                    "%X{request.id,tenant}")) {
                String xml = MigrationFixtures.single(provider, "APPROVED %msg request=" + context + "%n", "");
                var original = SourceLogging.source(provider, xml);
                assertEquals(2, original.out().size(), original.toString());
                if (context.equals("%X{request.id}")) {
                    assertTrue(original.out().getFirst().contains("request=request-7"));
                    assertFalse(original.out().getFirst().contains("UNRELATED-SECRET"));
                }
                if (context.equals("%X")) assertTrue(original.out().getFirst().contains("UNRELATED-SECRET"));
                if (context.equals("%X{missing:-fallback}") && provider.equals("logback")) {
                    assertTrue(original.out().getFirst().contains("request=fallback"));
                }
                if (context.equals("%X{request.id,tenant}") && provider.equals("log4j2")) {
                    assertTrue(original.out().getFirst().contains("request.id=request-7"));
                    assertTrue(original.out().getFirst().contains("tenant=tenant-2"));
                    assertFalse(original.out().getFirst().contains("UNRELATED-SECRET"));
                }
                Conversion refused = convert(provider, xml, true);
                assertEquals(3, refused.status(), refused.err());
                assertEquals("", refused.toml());
                assertTrue(refused.err().contains("UNSUPPORTED"), refused.err());
            }
        }
    }

    private Conversion convert(String provider, String xml, boolean strict) throws Exception {
        Path input = directory.resolve(provider + ".xml");
        Files.writeString(input, xml);
        Path out = directory.resolve("stdout.txt");
        Path err = directory.resolve("stderr.txt");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath(), LogyardConfigTool.class.getName(), "migrate-" + provider, input.toString()));
        if (strict) command.add("--strict");
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "migration CLI timed out");
            return new Conversion(process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private static String classpath() throws Exception {
        var entries = new LinkedHashSet<>(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = LogyardConfigTool.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) entries.add(Path.of(url.toURI()).toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private record Conversion(int status, String toml, String err) { }
}
