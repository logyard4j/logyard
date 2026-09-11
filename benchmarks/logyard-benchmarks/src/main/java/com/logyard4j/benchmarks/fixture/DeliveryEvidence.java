package com.logyard4j.benchmarks.fixture;

import com.logyard4j.api.Level;
import com.logyard4j.core.delivery.async.AsyncSink;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconciled whole-iteration evidence, including JMH transition calls and explicit priming. */
public final class DeliveryEvidence {
    private DeliveryEvidence() {
    }

    public static void async(BenchmarkParams benchmark, IterationParams iteration, int sequence,
            String kind, long calls, long primed, AsyncSink sink, long observed) throws IOException {
        long accepted = sink.queuedEvents() + sink.synchronousFallbacks();
        long dropped = 0;
        for (Level level : Level.values()) {
            dropped += sink.dropped(level);
        }
        require(calls + primed == accepted + dropped + sink.emergencyFallbacks(), "async admission counts do not reconcile");
        require(accepted == sink.deliveredEvents() && accepted == observed, "accepted events did not all reach the delegate");
        require(sink.health("benchmark").metrics().get("outstanding_queued_events") == 0, "async drain did not complete");
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("benchmark_calls", calls);
        counts.put("primed", primed);
        counts.put("attempted", calls + primed);
        counts.put("accepted", accepted);
        counts.put("enqueued", sink.queuedEvents());
        counts.put("dropped", dropped);
        counts.put("synchronous_fallbacks", sink.synchronousFallbacks());
        counts.put("emergency_fallbacks", sink.emergencyFallbacks());
        counts.put("delegate_accepted", sink.deliveredEvents());
        counts.put("delegate_observed", observed);
        write(benchmark, iteration, sequence, kind, counts);
    }

    public static void write(BenchmarkParams benchmark, IterationParams iteration, int sequence,
            String kind, Map<String, Long> counts) throws IOException {
        StringBuilder json = new StringBuilder("{\"benchmark\":").append(quote(benchmark.getBenchmark()))
                .append(",\"phase\":").append(quote(iteration.getType().name()))
                .append(",\"kind\":").append(quote(kind))
                .append(",\"sequence\":").append(sequence)
                .append(",\"fork_pid\":").append(ProcessHandle.current().pid())
                .append(",\"threads\":").append(benchmark.getThreads()).append(",\"params\":{");
        boolean first = true;
        for (String key : benchmark.getParamsKeys()) {
            if (!first) json.append(',');
            first = false;
            json.append(quote(key)).append(':').append(quote(benchmark.getParam(key)));
        }
        json.append('}');
        counts.forEach((key, value) -> json.append(',').append(quote(key)).append(':').append(value));
        String record = json.append('}').toString();
        System.out.println("LOGYARD_DELIVERY " + record);
        String destination = System.getenv("LOGYARD_BENCHMARK_EVIDENCE");
        if (destination != null) {
            Path path = Path.of(destination);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, record + '\n', StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    public static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private static String quote(String token) {
        if (!token.matches("[A-Za-z0-9_.$-]+")) {
            throw new IllegalArgumentException("unexpected benchmark metadata token: " + token);
        }
        return '"' + token + '"';
    }
}
