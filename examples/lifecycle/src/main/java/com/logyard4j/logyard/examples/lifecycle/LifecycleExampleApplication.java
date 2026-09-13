package com.logyard4j.logyard.examples.lifecycle;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.jul.LogyardHandler;
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.logyard.runtime.bootstrap.RuntimeOwner;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/** Keeps facade logger objects across two coordinated process-wide runtime lifetimes. */
public final class LifecycleExampleApplication {
    private LifecycleExampleApplication() {
    }

    public static void main(String[] arguments) throws Exception {
        var slf4j = LoggerFactory.getLogger("lifecycle.slf4j");
        var system = System.getLogger("lifecycle.system");
        var jul = java.util.logging.Logger.getLogger("lifecycle.jul");
        var handler = new LogyardHandler();
        jul.setUseParentHandlers(false);
        jul.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        jul.addHandler(handler);
        try {
            for (int cycle = 0; cycle < 2; cycle++) {
                try (RuntimeBundle owner = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, configuration(cycle))) {
                    if (cycle == 0) {
                        slf4j.info("slf4j cycle {}", cycle);
                        jul.log(Level.INFO, "jul cycle {0}", cycle);
                        system.log(System.Logger.Level.INFO, "system cycle {0}", cycle);
                    } else {
                        slf4j.info("disabled after restart");
                        slf4j.warn("slf4j cycle {}", cycle);
                        jul.log(Level.WARNING, "jul cycle {0}", cycle);
                        system.log(System.Logger.Level.WARNING, "system cycle {0}", cycle);
                    }
                    owner.runtime().logger("lifecycle.native").warn("native cycle {}", cycle);
                }
                // Facades hold leases too; a coordinated restart explicitly retires every owner.
                Logyard.shutdown();
                long written = Files.readAllLines(Path.of(System.getenv("LOGYARD_EXAMPLE_OUTPUT"))).size();
                if (written != (cycle + 1) * 4L) throw new AssertionError("managed close did not drain exactly once: " + written);
            }
        } finally {
            jul.removeHandler(handler);
            handler.close();
            Logyard.shutdown();
        }
    }

    private static LogyardConfigurationSource configuration(int cycle) {
        return LogyardConfigurationSource.text("lifecycle-" + cycle, """
                schema = 1
                [runtime]
                watch = false
                [delivery]
                mode = "async"
                capacity = 64
                [loggers]
                root = { level = "%s", outputs = ["json"] }
                [outputs.json]
                type = "file"
                path = "${LOGYARD_EXAMPLE_OUTPUT}"
                append = true
                flush = "1s"
                """.formatted(cycle == 0 ? "info" : "warn"), Path.of("."));
    }
}
