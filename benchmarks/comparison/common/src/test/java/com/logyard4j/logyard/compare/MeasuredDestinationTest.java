package com.logyard4j.logyard.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeasuredDestinationTest {
    @TempDir
    Path directory;

    @Test
    void countsOnlyMeasuredWritesAndNeverEncodesTheDrainMarker() throws Exception {
        Path output = directory.resolve("events.jsonl");
        try (var destination = new MeasuredDestination(options(output))) {
            destination.acceptEncoded("00000000 warmup", "warmup\n", MeasuredDestinationTest::bytes);
            destination.beginMeasurement();
            destination.acceptEncoded("00000000 event", "measured\n", MeasuredDestinationTest::bytes);
            destination.acceptEncoded(Workload.BARRIER, "marker", ignored -> {
                throw new AssertionError("drain marker reached encoder");
            });
            assertTrue(destination.awaitBarrier(0));
            assertEquals("measured\n", Files.readString(output));
            assertEquals(Files.size(output), destination.bytes());
            assertTrue(destination.completionTimes()[0] > 0);
            assertEquals(0, destination.completionTimes()[1]);
            destination.requireHealthy();
        }
    }

    @Test
    void retainsEncoderFailureAfterDrainAndClose() throws Exception {
        Path output = directory.resolve("failure.jsonl");
        var destination = new MeasuredDestination(options(output));
        destination.beginMeasurement();
        assertThrows(IllegalArgumentException.class, () -> destination.acceptEncoded("00000000 event", "event", ignored -> {
            throw new IllegalArgumentException("broken encoder");
        }));
        destination.acceptEncoded(Workload.BARRIER, "marker", MeasuredDestinationTest::bytes);
        assertTrue(destination.awaitBarrier(0));
        assertEquals(0, Files.size(output));
        assertEquals(0, destination.completionTimes()[0]);
        assertThrows(IllegalStateException.class, destination::requireHealthy);
        assertThrows(IllegalStateException.class, destination::close);
        assertThrows(IllegalStateException.class, destination::requireHealthy);
    }

    @Test
    void rejectsNullEmptyAndUnframedEncodingBeforeWriting() throws Exception {
        for (String encoded : new String[] {null, "", "partial"}) {
            Path output = directory.resolve("framing-" + String.valueOf(encoded) + ".jsonl");
            var destination = new MeasuredDestination(options(output));
            destination.beginMeasurement();
            assertThrows(IllegalStateException.class, () -> destination.acceptEncoded("00000000 event", encoded,
                    value -> value == null ? null : bytes(value)));
            assertEquals(0, Files.size(output));
            assertThrows(IllegalStateException.class, destination::close);
        }
    }

    @Test
    void refusesToHideDuplicatesOrNativeEncoderBypass() throws Exception {
        var duplicate = new MeasuredDestination(options(directory.resolve("duplicate.jsonl")));
        duplicate.beginMeasurement();
        duplicate.acceptEncoded("00000000 event", "event\n", MeasuredDestinationTest::bytes);
        assertThrows(IllegalStateException.class, () -> duplicate.acceptEncoded("00000000 event", "duplicate\n",
                MeasuredDestinationTest::bytes));
        assertEquals(bytes("event\n").length, duplicate.bytes());
        assertThrows(IllegalStateException.class, duplicate::close);
        var bypass = new MeasuredDestination(options(directory.resolve("bypass.jsonl")));
        assertThrows(IllegalStateException.class, () -> bypass.accept("00000000 event", "ERROR", ignored -> null));
        assertThrows(IllegalStateException.class, bypass::close);
    }

    @Test
    void rejectsWritesAndDrainMarkersAfterClose() throws Exception {
        var destination = new MeasuredDestination(options(directory.resolve("closed.jsonl")));
        destination.close();
        assertThrows(IllegalStateException.class, () -> destination.acceptEncoded("00000000 event", "event\n",
                MeasuredDestinationTest::bytes));
        assertThrows(IllegalStateException.class, () -> destination.acceptEncoded(Workload.BARRIER, "marker",
                MeasuredDestinationTest::bytes));
    }

    private static RunOptions options(Path output) {
        return RunOptions.parse(new String[] {output.toString(), "2", "1", "0", "0", "native-json",
                "0", "0", "0", "false", "matched-drop", "none"});
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
