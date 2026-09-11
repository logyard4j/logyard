package com.logyard4j.compare;

import com.logyard4j.runtime.bootstrap.LogyardBootstrap;
import com.logyard4j.runtime.bootstrap.LogyardConfigurationSource;
import com.logyard4j.runtime.bootstrap.RuntimeBundle;
import com.logyard4j.runtime.bootstrap.RuntimeOwner;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/** Actual Logyard SLF4J discovery, capture, routing, and asynchronous output ownership. */
public final class Backend implements AutoCloseable {
    private final RuntimeBundle application;

    private Backend(RuntimeBundle application) {
        this.application = application;
    }

    public static Backend open(RunOptions options, MeasuredDestination destination) {
        ComparisonOutput.destination = destination;
        String keys = options.fieldNames().stream().map(JsonText::quote).collect(Collectors.joining(","));
        String capacity = options.policy().equals("matched-drop") ? """
                capacity = 4096
                [delivery.overflow]
                trace = "drop"
                debug = "drop"
                info = "drop"
                warn = "drop"
                error = "drop"
                """ : "";
        String configuration = """
                schema = 1
                [runtime]
                watch = false
                internal_status = "off"
                [delivery]
                mode = "async"
                %s
                [context]
                mdc = [%s]
                [loggers]
                root = { level = "info", outputs = ["comparison"] }
                [outputs.comparison]
                type = "custom"
                provider = "comparison"
                implementation = "com.logyard4j.compare.ComparisonOutput"
                %s
                """.formatted(capacity, keys, options.nativeJson() ? """
                encoder = "comparison"
                [encoders.comparison]
                type = "json"
                profile = "comparison"
                [json_profiles.comparison]
                preset = "logyard"
                rename = { severity_text = "level", body = "message" }
                drop = ["observed_timestamp_unix_nano", "severity_number", "logger", "event_name",
                        "message_template", "resource", "thread", "exception"%s]
                """.formatted(options.fields() == 0 ? ", \"attributes\"" : "") : "");
        return new Backend(LogyardBootstrap.acquire(RuntimeOwner.APPLICATION,
                LogyardConfigurationSource.text("delivery comparison", configuration, Path.of("."))));
    }

    public String name() {
        return "logyard";
    }

    public String workerToken() {
        return "logyard-output-comparison";
    }

    public long capacity() {
        return metrics().get("capacity");
    }

    public long extraBatchCapacity() {
        return metrics().get("maximum_batch_size");
    }

    public boolean hasCapacity() {
        Map<String, Long> metrics = metrics();
        return metrics.get("queued") < metrics.get("capacity");
    }

    public Map<String, Long> counters() {
        Map<String, Long> current = metrics();
        return Map.of("native_enqueued", current.get("enqueued_total"), "native_dropped", current.get("dropped_total"));
    }

    private Map<String, Long> metrics() {
        return application.runtime().health().components().stream()
                .filter(component -> component.metrics().containsKey("capacity"))
                .findFirst().orElseThrow().metrics();
    }

    @Override
    public void close() {
        com.logyard4j.api.Logyard.shutdown();
        application.close();
        ComparisonOutput.destination = null;
    }
}
