package com.logyard4j.logyard.compare;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Explicit, bounded inputs for one isolated delivery experiment. */
public record RunOptions(Path output, int events, int producers, int arguments, int fields, String format,
        long rate, long stallMillis, long delayMicros, ThreadModel threadModel, String policy, String disabled) {
    public static RunOptions parse(String[] args) {
        if (args.length != 12) throw new IllegalArgumentException("expected 12 comparison arguments");
        RunOptions options = new RunOptions(Path.of(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                Integer.parseInt(args[3]), Integer.parseInt(args[4]), args[5], Long.parseLong(args[6]),
                Long.parseLong(args[7]), Long.parseLong(args[8]), ThreadModel.parse(args[9]), args[10], args[11]);
        if (options.events < 1 || options.events > 200_000 || !List.of(1, 4, 16, 64).contains(options.producers)
                || !List.of(0, 1, 2, 4).contains(options.arguments) || !List.of(0, 4, 16).contains(options.fields)
                || !List.of("text", "json", "native-json").contains(options.format) || options.rate < 0 || options.rate > 10_000_000
                || options.stallMillis < 0 || options.stallMillis > 5_000 || options.delayMicros < 0 || options.delayMicros > 10_000
                || !List.of("matched-drop", "default").contains(options.policy)
                || !List.of("none", "classic", "fluent", "supplier").contains(options.disabled)) {
            throw new IllegalArgumentException("unsupported comparison parameter");
        }
        if (options.format.equals("text") && options.fields != 0) {
            throw new IllegalArgumentException("structured fields require the JSON workload");
        }
        if (options.rate > 0 && options.events * 1_000_000_000L / options.rate > 60_000_000_000L) {
            throw new IllegalArgumentException("scheduled arrivals must fit within 60 seconds");
        }
        return options;
    }

    public List<String> fieldNames() {
        ArrayList<String> names = new ArrayList<>();
        for (int index = 0; index < fields; index++) names.add("field." + index);
        return List.copyOf(names);
    }

    public boolean enabled() {
        return disabled.equals("none");
    }

    public boolean nativeJson() {
        return format.equals("native-json");
    }

    public boolean virtualThreads() {
        return threadModel != ThreadModel.PLATFORM;
    }

    public boolean virtualPerRequest() {
        return threadModel == ThreadModel.VIRTUAL_PER_REQUEST;
    }
}
