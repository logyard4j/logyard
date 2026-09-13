package com.logyard4j.compare;

import com.logyard4j.api.Logyard;
import com.logyard4j.api.diagnostics.ComponentHealth;
import com.logyard4j.api.diagnostics.HealthStatus;
import com.logyard4j.api.reload.ReloadResult;
import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Packaged buffered-file fanout with exceptions, cached loggers, and reload during publication. */
public final class ReloadDeliveryMain {
    private ReloadDeliveryMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("expected directory, events, and arrival rate");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        RunOptions options = RunOptions.parse(new String[] {directory.resolve("first.jsonl").toString(),
                args[1], "16", "2", "4", "native-json", args[2], "0", "0", "false", "default", "none"});
        require(options.rate() > 0 && options.events() * 1_000L / options.rate() >= 1_000,
                "reload workloads require at least one second of scheduled arrivals");
        Files.createDirectory(directory);
        Path source = directory.resolve("logyard.toml");
        ReloadConfiguration.write(source, false);
        RuntimeBundle application = LogyardBootstrap.start(source);
        try {
            Logger logger = LoggerFactory.getLogger("comparison");
            require(logger.isInfoEnabled() && !logger.isDebugEnabled(), "unexpected initial logger route");
            ReloadWorkload workload = new ReloadWorkload(options);
            try (ProducerTeam producers = new ProducerTeam(options, workload, logger)) {
                producers.awaitWarmup();
                awaitDelivered(application);
                application.runtime().flush();
                Map<String, Map<String, Long>> before = metrics(application);
                ProcessMetrics cpuBefore = ProcessMetrics.capture(producers.threadIds(), "logyard-output-");
                long wallStartedAt = System.currentTimeMillis();
                workload.beginMeasurement();
                long startedAt = producers.begin();
                Map<String, Long> counts = reload(application, source, logger, producers);
                producers.awaitCalls();
                long callsEndedAt = Long.MIN_VALUE;
                for (int index = 0; index < options.events(); index++) {
                    callsEndedAt = Math.max(callsEndedAt,
                            producers.arrivals()[index] + producers.lateness()[index] + producers.callerTimes()[index]);
                }
                awaitDelivered(application);
                application.runtime().flush();
                long drainedAt = System.nanoTime();
                long wallDrainedAt = System.currentTimeMillis();
                Map<String, Map<String, Long>> after = metrics(application);
                Map<String, Long> cpu = ProcessMetrics.capture(producers.threadIds(), "logyard-output-").since(cpuBefore, false);
                require(cpu.get("worker_threads") == 2L, "expected two live output workers");
                counts.putAll(cpu);
                counts.put("attempted", (long) options.events());
                counts.put("warmup_attempted", (long) options.producers() * Math.max(200, 10_000 / options.producers()));
                counts.put("measurement_start_epoch_millis", wallStartedAt);
                counts.put("measurement_end_epoch_millis", wallDrainedAt);
                counts.put("calls_elapsed_ns", callsEndedAt - startedAt);
                counts.put("drained_elapsed_ns", drainedAt - startedAt);
                counts.put("drain_ns", drainedAt - callsEndedAt);
                long closingAt = System.nanoTime();
                close(application);
                counts.put("close_ns", System.nanoTime() - closingAt);
                require(application.runtime().health().status() == HealthStatus.STOPPED, "output retirement did not finish");
                counts.put("completion_epoch_millis", System.currentTimeMillis());
                quantiles(counts, producers.callerTimes());
                report(counts, before, after);
            }
        } finally {
            close(application);
        }
    }

    private static Map<String, Long> reload(RuntimeBundle application, Path source, Logger logger,
            ProducerTeam producers) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        long applied = 0;
        long maximumDuration = 0;
        while (!producers.awaitCalls(250, TimeUnit.MILLISECONDS)) {
            require(System.nanoTime() < deadline, "publication did not finish during reload");
            boolean debug = applied % 2 == 0;
            ReloadConfiguration.write(source, debug);
            long began = System.nanoTime();
            require(application.reloadNow() == ReloadResult.APPLIED, "configuration reload was not applied");
            maximumDuration = Math.max(maximumDuration, System.nanoTime() - began);
            require(logger.isDebugEnabled() == debug, "cached SLF4J logger did not follow reload");
            applied++;
        }
        require(applied > 0, "no reload overlapped the producer workload");
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("reloads", applied);
        counts.put("reload_max_ns", maximumDuration);
        return counts;
    }

    private static void awaitDelivered(RuntimeBundle application) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            boolean drained = metrics(application).values().stream().allMatch(value ->
                    value.get("enqueued_total").equals(value.get("delivered_total"))
                            && value.get("outstanding_queued_events") == 0L);
            if (drained) return;
            require(System.nanoTime() < deadline, "buffered outputs did not drain");
            Thread.sleep(1);
        }
    }

    private static Map<String, Map<String, Long>> metrics(RuntimeBundle application) {
        Map<String, Map<String, Long>> outputs = new LinkedHashMap<>();
        for (ComponentHealth component : application.runtime().health().components()) {
            if (!component.metrics().containsKey("enqueued_total")) continue;
            require("healthy".equals(component.details().get("delegate_status")), "file output is not healthy");
            outputs.put(component.name(), component.metrics());
        }
        require(outputs.keySet().equals(java.util.Set.of("first", "second")), "unexpected output membership");
        return outputs;
    }

    private static void close(RuntimeBundle application) {
        Logyard.shutdown();
        application.close();
    }

    private static void quantiles(Map<String, Long> counts, long[] samples) {
        Arrays.sort(samples);
        for (double percentile : new double[] {0.5, 0.99, 0.999, 1.0}) {
            String name = percentile == 1.0 ? "max" : percentile == 0.5 ? "p50" : percentile == 0.99 ? "p99" : "p999";
            counts.put("caller_" + name + "_ns", samples[Math.min(samples.length - 1,
                    (int) Math.ceil(samples.length * percentile) - 1)]);
        }
    }

    private static void report(Map<String, Long> counts, Map<String, Map<String, Long>> before,
            Map<String, Map<String, Long>> after) {
        StringBuilder json = new StringBuilder("{");
        counts.forEach((key, value) -> json.append(JsonText.quote(key)).append(':').append(value).append(','));
        json.append("\"outputs\":{");
        for (String name : new String[] {"first", "second"}) {
            if (name.equals("second")) json.append(',');
            Map<String, Long> initial = before.get(name);
            Map<String, Long> current = after.get(name);
            long enqueued = current.get("enqueued_total") - initial.get("enqueued_total");
            long dropped = current.get("dropped_total") - initial.get("dropped_total");
            require(enqueued >= 0 && dropped >= 0 && enqueued + dropped == counts.get("attempted"),
                    "output counters changed identity or do not reconcile");
            require(current.get("delivered_total").equals(current.get("enqueued_total")), "accepted records did not finish");
            json.append(JsonText.quote(name)).append(":{\"enqueued\":").append(enqueued)
                    .append(",\"dropped\":").append(dropped)
                    .append(",\"warmup_enqueued\":").append(initial.get("enqueued_total"))
                    .append(",\"warmup_dropped\":").append(initial.get("dropped_total"))
                    .append(",\"capacity\":").append(current.get("capacity"))
                    .append(",\"worker_batch_capacity_outside_queue\":").append(current.get("maximum_batch_size"))
                    .append('}');
        }
        System.out.println("RELOAD_RESULT " + json.append("}}"));
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
