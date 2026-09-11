package com.logyard4j.examples.migration;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.runtime.assembly.LogyardRuntimeFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Captures actual console destinations after provider shutdown has drained queued records. */
final class SourceLogging {
    record Records(List<String> out, List<String> err) { }
    @FunctionalInterface private interface Action { void run() throws Exception; }

    private SourceLogging() { }

    static Records source(String provider, String xml) throws Exception {
        return capture(() -> {
            if (provider.equals("logback")) logback(xml); else log4j(xml);
        });
    }

    static Records migrated(String toml) throws Exception {
        return capture(() -> {
            var config = LogyardConfigLoader.parse(toml, "migration.toml", Path.of("."), Map.of());
            try (var runtime = LogyardRuntimeFactory.create(config)) {
                var log = runtime.logger("migration.fixture");
                log.debug("FILTERED-SENTINEL");
                log.atInfo().add("private", "UNRELATED-SECRET").log("MIGRATION-EVENT info");
                log.warn("MIGRATION-EVENT warn");
            }
        });
    }

    private static void logback(String xml) throws Exception {
        LoggerContext context = new LoggerContext();
        try {
            context.setMDCAdapter(new LogbackMDCAdapter());
            context.getMDCAdapter().put("request.id", "request-7");
            context.getMDCAdapter().put("tenant", "tenant-2");
            context.getMDCAdapter().put("private", "UNRELATED-SECRET");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(input(xml));
            var log = context.getLogger("migration.fixture");
            log.debug("FILTERED-SENTINEL");
            log.info("MIGRATION-EVENT info");
            log.warn("MIGRATION-EVENT warn");
        } finally {
            context.stop();
        }
    }

    private static void log4j(String xml) throws Exception {
        try (var context = new org.apache.logging.log4j.core.LoggerContext("migration-fixture")) {
            ThreadContext.put("request.id", "request-7");
            ThreadContext.put("tenant", "tenant-2");
            ThreadContext.put("private", "UNRELATED-SECRET");
            context.start(new XmlConfiguration(context, new ConfigurationSource(input(xml))));
            var log = context.getLogger("migration.fixture");
            log.debug("FILTERED-SENTINEL");
            log.info("MIGRATION-EVENT info");
            log.warn("MIGRATION-EVENT warn");
        } finally {
            ThreadContext.clearAll();
        }
    }

    private static Records capture(Action action) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            action.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Records(records(out), records(err));
    }

    private static List<String> records(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains("APPROVED")
                || line.contains("MIGRATION-EVENT") || line.contains("FILTERED-SENTINEL")).toList();
    }

    private static ByteArrayInputStream input(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
