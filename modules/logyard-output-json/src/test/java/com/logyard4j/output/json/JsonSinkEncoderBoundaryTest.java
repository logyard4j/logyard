package com.logyard4j.output.json;

import com.logyard4j.api.Level;
import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.spi.encoding.EventEncoder;
import com.logyard4j.api.spi.encoding.EventEncoderBoundary;
import com.logyard4j.api.spi.output.EventSink;
import com.logyard4j.output.json.file.JsonFileSink;
import com.logyard4j.output.json.stream.JsonLinesSink;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonSinkEncoderBoundaryTest {
    private static final LogEvent EVENT = new LogEvent(
            0L, 0L, Level.INFO, "test.Logger", "test", "message", null, AttributeSet.EMPTY, null, 1L, "test");

    @Test
    void directFileSinkPreservesOnePhysicalRecordPerEvent() throws Exception {
        assertRecordContract(JsonSinkEncoderBoundaryTest::fileHarness);
    }

    @Test
    void directStreamSinkPreservesOnePhysicalRecordPerEvent() throws Exception {
        assertRecordContract(JsonSinkEncoderBoundaryTest::streamHarness);
    }

    @Test
    void directSinkConstructionRejectsInvalidMediaTypes() throws Exception {
        for (String mediaType : new String[] {
                "",
                "x".repeat(EventEncoderBoundary.MAX_MEDIA_TYPE_CHARACTERS + 1),
                "application/json\u0007"
        }) {
            EventEncoder encoder = encoder(new AtomicReference<>("{}"), mediaType);
            Path output = Files.createTempDirectory("logyard-media-boundary-").resolve("events.jsonl");
            assertThrows(IllegalArgumentException.class, () -> new JsonFileSink(
                    output, encoder, 1_024, Duration.ZERO, false, null));
            assertThrows(IllegalArgumentException.class, () -> new JsonLinesSink(
                    new StringWriter(), encoder, Duration.ZERO, false));
        }
    }

    private static void assertRecordContract(HarnessFactory factory) throws Exception {
        AtomicReference<String> encoded = new AtomicReference<>("{}");
        Harness harness = factory.open(encoder(encoded, "application/json"));
        try (harness) {
            for (String invalid : new String[] {
                    null,
                    "{\"first\":true}\n{\"second\":true}",
                    "{\"first\":true}\r{\"second\":true}",
                    "a".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES + 1),
                    "\u00e9".repeat(EventEncoderBoundary.MAX_ENCODED_UTF8_BYTES / 2 + 1)
            }) {
                encoded.set(invalid);
                assertThrows(RuntimeException.class, () -> harness.sink().accept(EVENT));
            }
            encoded.set("{\"valid\":true}");
            harness.sink().accept(EVENT);
        }
        assertEquals("{\"valid\":true}\n", harness.contents());
    }

    private static EventEncoder encoder(AtomicReference<String> encoded, String mediaType) {
        return new EventEncoder() {
            @Override
            public String encode(LogEvent event) {
                return encoded.get();
            }

            @Override
            public String mediaType() {
                return mediaType;
            }
        };
    }

    private static Harness fileHarness(EventEncoder encoder) throws Exception {
        Path output = Files.createTempDirectory("logyard-direct-encoder-").resolve("events.jsonl");
        EventSink sink = new JsonFileSink(output, encoder, 1_024, Duration.ZERO, false, null);
        return new Harness(sink, () -> Files.readString(output));
    }

    private static Harness streamHarness(EventEncoder encoder) {
        StringWriter writer = new StringWriter();
        EventSink sink = new JsonLinesSink(writer, encoder, Duration.ZERO, false);
        return new Harness(sink, writer::toString);
    }

    private record Harness(EventSink sink, Content content) implements AutoCloseable {
        @Override
        public void close() {
            sink.close();
        }

        String contents() throws Exception {
            return content.read();
        }
    }

    @FunctionalInterface
    private interface HarnessFactory {
        Harness open(EventEncoder encoder) throws Exception;
    }

    @FunctionalInterface
    private interface Content {
        String read() throws Exception;
    }
}
