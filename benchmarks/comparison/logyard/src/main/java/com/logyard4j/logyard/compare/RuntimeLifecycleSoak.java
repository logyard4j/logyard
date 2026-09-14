package com.logyard4j.logyard.compare;

import com.logyard4j.logyard.api.Logyard;
import com.logyard4j.logyard.api.context.LogContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Reuses the packaged reload workload in one JVM, with external per-cycle accounting. */
public final class RuntimeLifecycleSoak {
    @SuppressWarnings("try")
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected directory and cycle count");
        Path root = Path.of(args[0]);
        int cycles = Integer.parseInt(args[1]);
        require(cycles >= 1 && cycles <= 100_000, "expected 1 to 100000 cycles");
        var input = new BufferedReader(new InputStreamReader(System.in));
        long baselineHeap = 0;
        long baselineDescriptors = 0;
        int baselineThreads = 0;
        try (var worker = Executors.newSingleThreadExecutor()) {
            var contextual = LogContext.wrap(worker);
            for (int cycle = 1; cycle <= cycles; cycle++) {
                try (var scope = LogContext.push("cycle", cycle)) {
                    int expected = cycle;
                    var capture = new FutureTask<>(() ->
                            Integer.valueOf(expected).equals(LogContext.current().get("cycle")));
                    contextual.execute(capture);
                    require(capture.get(5, TimeUnit.SECONDS), "executor did not propagate this submission's context");
                }
                require(worker.submit(() -> LogContext.current().isEmpty()).get(5, TimeUnit.SECONDS),
                        "executor retained context after completion");
                Path directory = root.resolve("cycle-" + cycle);
                ReloadDeliveryMain.main(new String[] {directory.toString(), "20000", "2000"});
                require(!Logyard.isInitialized(), "process runtime remained installed after shutdown");
                System.gc();
                long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                var os = (com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                long descriptors = os.getOpenFileDescriptorCount();
                int threads = ManagementFactory.getThreadMXBean().getThreadCount();
                if (cycle == 10) {
                    baselineHeap = heap;
                    baselineDescriptors = descriptors;
                    baselineThreads = threads;
                }
                if (cycle > 10) {
                    require(heap <= baselineHeap + 32L * 1024 * 1024, "retained heap grew by more than 32 MiB");
                    require(descriptors <= baselineDescriptors + 4, "file descriptors accumulated across shutdowns");
                    require(threads <= baselineThreads + 4, "threads accumulated across shutdowns");
                }
                System.out.printf("SOAK_CYCLE {\"cycle\":%d,\"heap_after_gc\":%d,\"open_descriptors\":%d,"
                        + "\"live_threads\":%d,\"loaded_classes\":%d}%n", cycle, heap, descriptors, threads,
                        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
                System.out.flush();
                require("continue".equals(input.readLine()), "external record validation did not acknowledge this cycle");
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
