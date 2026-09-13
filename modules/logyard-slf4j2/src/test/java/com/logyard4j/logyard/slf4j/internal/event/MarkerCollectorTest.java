package com.logyard4j.logyard.slf4j.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.helpers.BasicMarkerFactory;

final class MarkerCollectorTest {
    @Test
    void traversesSharedMarkerGraphsOnceInStableOrder() {
        BasicMarkerFactory factory = new BasicMarkerFactory();
        Marker root = factory.getMarker("root");
        Marker child = factory.getMarker("child");
        Marker leaf = factory.getMarker("leaf");
        root.add(child);
        root.add(leaf);
        child.add(leaf);

        MarkerCollector.Result result = new MarkerCollector().collect(root);
        assertEquals(java.util.List.of("root", "child", "leaf"), result.names());
        assertFalse(result.truncated());
        assertEquals(0, result.captureFailures());
    }

    @Test
    void isolatesHostileMarkerAccessors() {
        MarkerCollector.Result result = new MarkerCollector().collect(new HostileMarker());
        assertEquals(java.util.List.of("hostile"), result.names());
        assertTrue(result.captureFailures() > 0);
        assertTrue(result.firstFailure() instanceof IllegalStateException);
    }

    @SuppressWarnings("deprecation")
    private static final class HostileMarker implements Marker {
        private static final long serialVersionUID = 1L;

        @Override public String getName() { return "hostile"; }
        @Override public void add(Marker reference) { }
        @Override public boolean remove(Marker reference) { return false; }
        @Override public boolean hasChildren() { return true; }
        @Override public boolean hasReferences() { return true; }
        @Override public Iterator<Marker> iterator() {
            throw new IllegalStateException("hostile iterator");
        }
        @Override public boolean contains(Marker other) { return false; }
        @Override public boolean contains(String name) { return false; }
    }

    @Test
    void doesNotTrustListIsEmpty() {
        List<Marker> hostile = new java.util.AbstractList<>() {
            @Override public Marker get(int index) { throw new IndexOutOfBoundsException(index); }
            @Override public int size() { return 0; }
            @Override public boolean isEmpty() { throw new AssertionError("isEmpty must not be called"); }
        };

        MarkerCollector.Result result = new MarkerCollector().collect(hostile);
        assertTrue(result.names().isEmpty());
        assertEquals(0, result.captureFailures());
    }
}
