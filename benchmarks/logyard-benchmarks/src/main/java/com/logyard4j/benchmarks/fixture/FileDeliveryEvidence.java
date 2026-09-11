package com.logyard4j.benchmarks.fixture;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Counts completed JSONL records after close, without retaining the file in memory. */
public final class FileDeliveryEvidence implements AutoCloseable {
    private final Path directory;
    private final Path path;

    public FileDeliveryEvidence() throws IOException {
        String configured = System.getenv("LOGYARD_BENCHMARK_DIRECTORY");
        Path parent = configured == null ? Path.of("target", "benchmark-files") : Path.of(configured);
        Files.createDirectories(parent);
        directory = Files.createTempDirectory(parent.toRealPath(), "json-");
        path = directory.resolve("events.jsonl");
    }

    public Path path() {
        return path;
    }

    public void verify(BenchmarkParams benchmark, IterationParams iteration, int sequence, long calls) throws IOException {
        long records = 0;
        long bytes = 0;
        int last = -1;
        byte[] buffer = new byte[65_536];
        try (InputStream input = Files.newInputStream(path)) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                bytes += length;
                for (int index = 0; index < length; index++) {
                    if (buffer[index] == '\n') records++;
                }
                if (length > 0) last = buffer[length - 1];
            }
        }
        DeliveryEvidence.require(records == calls, "file record count does not match benchmark calls");
        DeliveryEvidence.require(bytes == 0 || last == '\n', "file ends with a partial record");
        DeliveryEvidence.write(benchmark, iteration, sequence, "file", Map.of(
                "benchmark_calls", calls, "sink_written", records, "file_bytes", bytes));
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
        Files.deleteIfExists(directory.resolve("events.jsonl.logyard.lock"));
        Files.deleteIfExists(directory);
    }
}
