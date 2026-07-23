package com.zsumz.logyard.output.json.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.LogEvent;
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

}
