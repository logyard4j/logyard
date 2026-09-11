package com.logyard4j.output.json.encoding;

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
        JsonWriter writer = new JsonWriter(32);

        writer.beginArray();
        writer.value(cyclic);
        writer.comma();
        writer.value(Double.NaN);
        writer.endArray();

        assertEquals("[[\"[shared reference]\"],\"NaN\"]", writer.result());
    }

    @Test
    void escapesControlCharactersAndSurrogates() {
        JsonWriter writer = new JsonWriter(32);

        writer.string("line\n\uD800");

        assertEquals("\"line\\n\\ud800\"", writer.result());
    }

    @Test
    void releasesAnUnusuallyLargeReusableBuffer() {
        JsonWriter writer = new JsonWriter(32);
        writer.string("x".repeat(100_000));
        writer.reset();

        assertTrue(writer.retainedCapacity() <= 4_096);
    }
}
