package com.logyard4j.logyard.output.json.file;

import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.event.LogEvent;
import com.logyard4j.logyard.api.spi.encoding.EventEncoder;
import com.logyard4j.logyard.output.json.encoding.JsonEncoder;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonFileUtf8Test {
    @TempDir
    Path directory;

    @Test
    void reusableRecordPrefixesMatchTextOutputAndExactRotationBoundaries() throws Exception {
        JsonEncoder encoder = new JsonEncoder(new ResourceAttributes(Map.of()));
        Path utf8 = Files.createDirectory(directory.resolve("utf8"));
        Path text = Files.createDirectory(directory.resolve("text"));
        write(utf8, encoder);
        write(text, encoder::encode);
        List<String> actual = contents(utf8);
        assertEquals(contents(text), actual);
        assertTrue(actual.size() > 10, "fixture must exercise rotation repeatedly");
        assertEquals(101, actual.stream().mapToLong(content -> content.lines().count()).sum());
    }

    @Test
    void fileBufferConsumesOnlyThePrefixAndDoesNotRetainReusableInput() throws Exception {
        Path path = directory.resolve("prefix.jsonl");
        byte[] reusable = new byte[4_096];
        try (BufferedFileWriter writer = BufferedFileWriter.open(path, 1_024, false)) {
            byte[] large = ("{\"text\":\"" + "界".repeat(600) + "\"}").getBytes(StandardCharsets.UTF_8);
            System.arraycopy(large, 0, reusable, 0, large.length);
            writer.write(reusable, large.length, (byte) '\n');
            reusable[0] = '{';
            reusable[1] = '}';
            writer.write(reusable, 2, (byte) '\n');
            java.util.Arrays.fill(reusable, (byte) 'x');
        }
        assertEquals("{\"text\":\"" + "界".repeat(600) + "\"}\n{}\n", Files.readString(path));
    }

    private static void write(Path directory, EventEncoder encoder) {
        RotationPolicy rotation = new RotationPolicy(1_024, 200, RotationPolicy.Compression.NONE, Duration.ofSeconds(5));
        try (JsonFileSink sink = new JsonFileSink(directory.resolve("events.jsonl"), encoder,
                1_024, Duration.ZERO, false, rotation)) {
            for (int sequence = 0; sequence <= 100; sequence++) {
                String message = sequence == 0 ? "界".repeat(8_000) : "record " + sequence + " 界😀\n";
                sink.accept(new LogEvent(0, 1, Level.INFO, "test", null, message, null,
                        AttributeSet.of("sequence", sequence), null, 1, "main"));
            }
        }
    }

    private static List<String> contents(Path directory) throws Exception {
        List<String> records = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                assertTrue(content.endsWith("\n"));
                records.add(content);
            }
        }
        return records.stream().sorted().toList();
    }
}
