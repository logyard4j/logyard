package com.logyard4j.logyard.output.json.encoding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonWriterTest {
    @Test
    void boundsCyclesAndEncodesNonFiniteNumbersAsStrings() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        JsonBuffer buffer = new JsonBuffer(32, JsonOutputLimits.MAX_RECORD_CHARACTERS);
        JsonWriter writer = new JsonWriter(buffer);

        writer.beginArray();
        writer.value(cyclic);
        writer.comma();
        writer.value(Double.NaN);
        writer.endArray();

        assertEquals("[[\"[shared reference]\"],\"NaN\"]", buffer.result());
    }

    @Test
    void escapesControlCharactersAndSurrogates() {
        JsonBuffer buffer = new JsonBuffer(32, JsonOutputLimits.MAX_RECORD_CHARACTERS);
        JsonWriter writer = new JsonWriter(buffer);

        writer.string("line\n\uD800");

        assertEquals("\"line\\n\\ud800\"", buffer.result());
    }

    @Test
    void releasesAnUnusuallyLargeReusableBuffer() {
        JsonBuffer buffer = new JsonBuffer(32, JsonOutputLimits.MAX_RECORD_CHARACTERS);
        JsonWriter writer = new JsonWriter(buffer);
        writer.string("x".repeat(100_000));
        writer.reset();

        assertTrue(buffer.capacity() <= 4_096);
    }
}
