package com.zsumz.logyard.runtime.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.reload.ReloadResult;
import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ManagedRuntimeHandoffTest {
    @Test
    void frameworkHandoffFailsExplicitlyWhileManualReloadOwnsTheInstallationTransition() throws Exception {
        TestGlobal global = new TestGlobal();
        RuntimeInstallationManager manager = new RuntimeInstallationManager(global, ignored -> true, Map::of);
        BlockingSource source = new BlockingSource(config("info"));
        RuntimeInstallationLease application = manager.acquireApplication(source.request("application"));
        LogyardLogger logger = application.runtime().logger("example.Service");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ReloadResult> reload = executor.submit(application::reloadNow);
            assertTrue(source.reloadEntered.await(1, TimeUnit.SECONDS));

            RuntimeTransitionInProgressException failure = assertThrows(
                    RuntimeTransitionInProgressException.class,
                    () -> manager.acquireFramework(request("framework", config("debug"), new Object())));

            assertTrue(failure.getMessage().contains("retry"));
            assertTrue(application.active());
            assertFalse(logger.isDebugEnabled());
            assertEquals(1, manager.leaseCount(RuntimeOwner.APPLICATION));
            assertEquals(0, manager.leaseCount(RuntimeOwner.FRAMEWORK));

            source.allowReload.countDown();
            assertEquals(ReloadResult.UNCHANGED, reload.get(2, TimeUnit.SECONDS));
            assertFalse(logger.isDebugEnabled());
        } finally {
            source.allowReload.countDown();
            application.close();
            executor.shutdownNow();
        }
    }

    private static ConfigurationInstallationRequest request(String description, String text, Object identity) {
        return new ConfigurationInstallationRequest(
                description,
                null,
                identity,
                () -> snapshot(description, text));
    }

    private static ConfigurationSnapshot snapshot(String description, String text) throws IOException {
        return ConfigurationSnapshot.capture(
                description,
                Path.of(".").toAbsolutePath(),
                null,
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static String config(String level) {
        return """
                schema = 1
                [runtime]
                watch = false
                shutdown_timeout = "2s"
                internal_status = "off"
                [delivery]
                mode = "sync"
                capacity = 16
                [loggers]
                root = { level = "%s", outputs = ["console"] }
                [outputs.console]
                type = "console"
                stream = "stderr"
                color = { mode = "never" }
                """.formatted(level);
    }

    private static final class BlockingSource {
        private final String text;
        private final AtomicInteger reads = new AtomicInteger();
        private final CountDownLatch reloadEntered = new CountDownLatch(1);
        private final CountDownLatch allowReload = new CountDownLatch(1);

        private BlockingSource(String text) {
            this.text = text;
        }

        ConfigurationInstallationRequest request(String description) {
            return new ConfigurationInstallationRequest(
                    description,
                    null,
                    this,
                    () -> {
                        if (reads.incrementAndGet() > 1) {
                            reloadEntered.countDown();
                            try {
                                allowReload.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("reload interrupted", interrupted);
                            }
                        }
                        return snapshot(description, text);
                    });
        }
    }

    private static final class TestGlobal implements GlobalRuntimeAccess {
        private final AtomicReference<LogyardRuntime> current = new AtomicReference<>();

        @Override public LogyardRuntime current() { return current.get(); }

        @Override
        public void install(LogyardRuntime runtime) {
            if (!current.compareAndSet(null, runtime)) {
                throw new IllegalStateException("runtime already installed");
            }
        }

        @Override
        public boolean shutdownIfCurrent(LogyardRuntime runtime) {
            if (!current.compareAndSet(runtime, null)) {
                return false;
            }
            runtime.close();
            return true;
        }
    }
}
