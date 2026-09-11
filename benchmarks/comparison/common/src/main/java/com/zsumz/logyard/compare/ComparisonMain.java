package com.zsumz.logyard.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One warmed, bounded experiment in a JVM containing exactly one SLF4J provider. */
public final class ComparisonMain {
    private ComparisonMain() {
    }

    public static void main(String[] args) throws Exception {
        RunOptions options = RunOptions.parse(args);
        Workload workload = new Workload(options);
        try (MeasuredDestination destination = new MeasuredDestination(options);
                Backend backend = Backend.open(options, destination)) {
            Logger logger = LoggerFactory.getLogger("comparison");
            try (ProducerTeam producers = new ProducerTeam(options, workload, logger)) {
                producers.awaitWarmup();
                drain(backend, destination, logger);
                destination.beginMeasurement();
                Map<String, Long> countersBefore = backend.counters();
                ProcessMetrics before = ProcessMetrics.capture(producers.threadIds(), backend.workerToken());
                long startedAt = producers.begin();
                producers.awaitCalls();
                long callsEndedAt = System.nanoTime();
                drain(backend, destination, logger);
                long drainedAt = System.nanoTime();
                ProcessMetrics after = ProcessMetrics.capture(producers.threadIds(), backend.workerToken());
                destination.requireHealthy();
                if (workload.supplierCalls() != 0) throw new IllegalStateException("disabled supplier was evaluated");
                Map<String, Long> metrics = after.since(before, options.virtualThreads());
                backend.counters().forEach((key, value) -> metrics.put(key, value - countersBefore.get(key)));
                if (metrics.get("worker_threads") != 1) throw new IllegalStateException("expected exactly one identifiable output worker");
                report(options, backend, destination, producers, metrics, startedAt, callsEndedAt, drainedAt);
            }
        }
    }

    private static void drain(Backend backend, MeasuredDestination destination, Logger logger) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!backend.hasCapacity()) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("no queue capacity for drain barrier");
            Thread.sleep(1);
        }
        logger.error(Workload.BARRIER);
        if (!destination.awaitBarrier(30_000)) throw new IllegalStateException("output drain barrier timed out");
        destination.requireHealthy();
    }

    private static void report(RunOptions options, Backend backend, MeasuredDestination destination,
            ProducerTeam producers, Map<String, Long> metrics, long startedAt, long callsEndedAt, long drainedAt) {
        long[] completion = destination.completionTimes();
        long[] latencies = new long[completion.length];
        int written = 0;
        long missingInfo = 0;
        long missingError = 0;
        for (int index = 0; index < completion.length; index++) {
            if (completion[index] != 0) {
                long latency = completion[index] - producers.arrivals()[index];
                if (latency < 0) throw new IllegalStateException("completion precedes scheduled arrival");
                latencies[written++] = latency;
            } else if (options.enabled()) {
                if (index % 8 == 0) missingError++;
                else missingInfo++;
            }
        }
        if (!options.enabled() && written != 0) throw new IllegalStateException("disabled calls reached output");
        if (metrics.containsKey("native_dropped") && metrics.get("native_dropped") != missingInfo + missingError) {
            throw new IllegalStateException("native drop counter differs from unwritten identities");
        }
        if (metrics.containsKey("native_enqueued")) {
            // The single drain marker follows all measured offers and is excluded from record counts.
            metrics.compute("native_enqueued", (key, value) -> value - 1);
            if (metrics.get("native_enqueued") != written) throw new IllegalStateException("enqueued records did not all complete");
        }
        Map<String, Long> counts = new LinkedHashMap<>(metrics);
        counts.put("attempted", (long) options.events());
        counts.put("filtered", options.enabled() ? 0L : options.events());
        counts.put("sink_written", (long) written);
        counts.put("unwritten_info", missingInfo);
        counts.put("unwritten_error", missingError);
        counts.put("destination_failures", 0L);
        counts.put("supplier_evaluations", 0L);
        counts.put("written_bytes", destination.bytes());
        counts.put("calls_elapsed_ns", callsEndedAt - startedAt);
        counts.put("drained_elapsed_ns", drainedAt - startedAt);
        counts.put("last_write_elapsed_ns", written == 0 ? 0L : destination.lastWrite() - startedAt);
        quantiles(counts, "caller", producers.callerTimes());
        quantiles(counts, "scheduled_arrival_lateness", producers.lateness());
        quantiles(counts, "arrival_to_write", Arrays.copyOf(latencies, written));
        StringBuilder json = new StringBuilder("{\"backend\":").append(JsonText.quote(backend.name()))
                .append(",\"jdk\":").append(JsonText.quote(System.getProperty("java.runtime.version")))
                .append(",\"queue_capacity\":").append(backend.capacity())
                .append(",\"worker_batch_capacity_outside_queue\":").append(backend.extraBatchCapacity())
                .append(",\"policy\":").append(JsonText.quote(options.policy()));
        counts.forEach((key, value) -> json.append(',').append(JsonText.quote(key)).append(':').append(value));
        System.out.println("COMPARISON_RESULT " + json.append('}'));
    }

    private static void quantiles(Map<String, Long> values, String name, long[] samples) {
        Arrays.sort(samples);
        for (double percentile : new double[] {0.5, 0.99, 0.999, 1.0}) {
            String suffix = percentile == 1.0 ? "max" : percentile == 0.5 ? "p50" : percentile == 0.99 ? "p99" : "p999";
            values.put(name + '_' + suffix + "_ns", samples.length == 0 ? -1L
                    : samples[Math.min(samples.length - 1, (int) Math.ceil(percentile * samples.length) - 1)]);
        }
    }
}
