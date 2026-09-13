package com.logyard4j.logyard.slf4j.internal.event;

import com.logyard4j.logyard.api.event.AttributeSet;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Slf4jKeyValueCollectorTest {
    @Test
    void extractsTheEventNameAndCountsInvalidPairs() {
        List<KeyValuePair> pairs = List.of(
                new KeyValuePair("event.name", "order.accepted"),
                new KeyValuePair("", "invalid"),
                new KeyValuePair("answer", 42));
        DeferredAttributes attributes = new DeferredAttributes(AttributeSet.EMPTY);

        String eventName = new Slf4jKeyValueCollector().collect(attributes, pairs, new Slf4jCaptureFailures());
        AttributeSet captured = attributes.build();

        assertEquals("order.accepted", eventName);
        assertEquals(42, captured.get("answer"));
        assertEquals(1, captured.get("logyard.slf4j.invalid_key_values"));
    }

    @Test
    void boundsVisitedPairsEvenWhenTheyDoNotConsumeAttributeCapacity() {
        List<KeyValuePair> pairs = new ArrayList<>();
        for (int index = 0; index <= Slf4jKeyValueCollector.MAX_PAIRS; index++) {
            pairs.add(new KeyValuePair("event.name", "event-" + index));
        }
        DeferredAttributes attributes = new DeferredAttributes(AttributeSet.EMPTY);

        String eventName = new Slf4jKeyValueCollector().collect(attributes, pairs, new Slf4jCaptureFailures());
        AttributeSet captured = attributes.build();

        assertEquals("event-" + (Slf4jKeyValueCollector.MAX_PAIRS - 1), eventName);
        assertEquals(true, captured.get("logyard.slf4j.key_values.truncated"));
    }

    @Test
    void containsHostileCollectionAccessAsACaptureFailure() {
        List<KeyValuePair> hostile = new AbstractList<>() {
            @Override
            public KeyValuePair get(int index) {
                throw new AssertionError("get must not be called");
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public Iterator<KeyValuePair> iterator() {
                throw new IllegalStateException("broken iterator");
            }
        };
        DeferredAttributes attributes = new DeferredAttributes(AttributeSet.EMPTY);
        Slf4jCaptureFailures failures = new Slf4jCaptureFailures();

        assertNull(new Slf4jKeyValueCollector().collect(attributes, hostile, failures));
        failures.annotate(attributes);

        assertTrue((Integer) attributes.build().get("logyard.slf4j.capture_failures") > 0);
    }
}
