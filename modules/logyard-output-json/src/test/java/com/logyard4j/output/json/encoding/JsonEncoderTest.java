package com.logyard4j.output.json.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.api.event.AttributeSet;
import com.logyard4j.api.Level;
import com.logyard4j.api.event.LogEvent;
import com.logyard4j.api.event.CaptureLimits;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonEncoderTest {
    @Test
    void encodesTypedValuesAndEscapesStrings() {
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"));
        String json = encoder.encode(new LogEvent(
                0,
                12_000_000,
                Level.INFO,
                "orders.Service",
                "order.accepted",
                "accepted {}",
                new Object[] {7},
                AttributeSet.builder()
                        .put("order.id", 7L)
                        .put("paid", true)
                        .put("tags", List.of("a", "b"))
                        .put("unsafe", "x\ny")
                        .build(),
                null,
                4,
                "main"));
        assertTrue(json.contains("\"order.id\":7"));
        assertTrue(json.contains("\"paid\":true"));
        assertTrue(json.contains("\"tags\":[\"a\",\"b\"]"));
        assertTrue(json.contains("\"unsafe\":\"x\\ny\""));
        assertTrue(json.contains("\"observed_timestamp_unix_nano\":12000000"));
        assertEquals('{', json.charAt(0));
        assertEquals('}', json.charAt(json.length() - 1));
    }

    @Test
    void snapshotsAndBoundsResourceValues() {
        List<String> mutable = new ArrayList<>(List.of("before"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("resource.items", mutable);
        for (int index = 0; index < 200; index++) {
            source.put("resource." + index, index);
        }
        ResourceAttributes resource = new ResourceAttributes(source);
        mutable.add("after");
        source.put("late", true);

        @SuppressWarnings("unchecked")
        List<String> captured = (List<String>) resource.values().get("resource.items");
        assertEquals(List.of("before"), captured);
        assertTrue(resource.values().size() <= 128);
        assertEquals(true, resource.values().get("logyard.attributes.truncated"));
    }

    @Test
    void rejectsRenamedAttributesThatCollideWithAnUnchangedAttribute() {
        JsonProfile profile = JsonProfile.custom(
                "collision",
                "logyard",
                Map.of(),
                List.of(),
                new JsonAttributeTransform(
                        JsonAttributeTransform.Mode.NESTED,
                        null,
                        List.of(),
                        List.of(),
                        Map.of("first", "second")));
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"), profile);
        LogEvent event = new LogEvent(
                0,
                1,
                Level.INFO,
                "orders.Service",
                null,
                "collision",
                null,
                AttributeSet.builder().put("first", 1).put("second", 2).build(),
                null,
                1,
                "main");

        assertThrows(IllegalArgumentException.class, () -> encoder.encode(event));
    }

    @Test
    void emitsCompactExactDecimalsAsJsonNumbers() {
        BigDecimal compactExponent = new BigDecimal(BigInteger.ONE, -1_000_000);
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"));

        String json = encoder.encode(event(
                "{}",
                new Object[] {compactExponent},
                AttributeSet.of("amount", compactExponent),
                null));

        assertTrue(json.contains("\"amount\":1E+1000000"));
        assertTrue(json.contains("\"body\":\"1E+1000000\""));
        JsonSyntaxValidator.requireValid(json);
    }

    @Test
    void boundsSharedValueAndExceptionGraphsWithoutExpandingAliases() {
        List<Object> shared = new ArrayList<>();
        shared.add("x".repeat(1_000));
        List<Object> root = new ArrayList<>();
        for (int index = 0; index < CaptureLimits.MAX_COLLECTION_ELEMENTS; index++) {
            root.add(shared);
        }
        IllegalStateException repeated = new IllegalStateException("shared");
        RuntimeException failure = new RuntimeException("parent");
        for (int index = 0; index < 8; index++) {
            failure.addSuppressed(repeated);
        }
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"));

        String json = encoder.encode(event("{}", new Object[] {root}, AttributeSet.of("graph", root), failure));

        assertTrue(json.length() < 100_000);
        assertTrue(json.contains("[shared reference]"));
        assertTrue(json.contains("[shared exception reference]"));
        JsonSyntaxValidator.requireValid(json);
    }

    @Test
    void fallsBackToACompleteJsonRecordWhenEscapingExhaustsTheOutputBudget() {
        String controls = "\u0000".repeat(CaptureLimits.MAX_EVENT_PAYLOAD_TEXT_CHARS);
        ResourceAttributes resource = new ResourceAttributes(Map.of("resource.noisy", controls));
        JsonEncoder encoder = new JsonEncoder(resource);
        IllegalStateException failure = new IllegalStateException(
                "\u0000".repeat(CaptureLimits.MAX_EVENT_EXCEPTION_MESSAGE_CHARS));

        String json = encoder.encode(event("{}", new Object[] {controls}, AttributeSet.EMPTY, failure));

        assertTrue(json.length() <= JsonOutputLimits.MAX_RECORD_CHARACTERS);
        assertTrue(json.contains("\"logyard.output.truncated\":true"));
        JsonSyntaxValidator.requireValid(json);
    }

    @Test
    void reportsWhenLazyMessageRenderingWasTruncated() {
        List<List<Long>> denseNumbers = new ArrayList<>();
        for (int row = 0; row < 31; row++) {
            denseNumbers.add(java.util.Collections.nCopies(
                    CaptureLimits.MAX_COLLECTION_ELEMENTS,
                    Long.MAX_VALUE));
        }
        JsonEncoder encoder = new JsonEncoder(ResourceAttributes.service("orders", "test", "1"));

        String json = encoder.encode(event("{}", new Object[] {denseNumbers}, AttributeSet.EMPTY, null));

        assertTrue(json.contains("\"logyard.output.truncated\":true"));
        JsonSyntaxValidator.requireValid(json);
    }

    private static LogEvent event(
            String template,
            Object[] arguments,
            AttributeSet attributes,
            Throwable failure) {
        return new LogEvent(
                0,
                1,
                Level.INFO,
                "orders.Service",
                null,
                template,
                arguments,
                attributes,
                failure,
                1,
                "main");
    }
}
