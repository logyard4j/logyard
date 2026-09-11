package com.logyard4j.slf4j.internal.context;

import com.logyard4j.api.event.CaptureLimits;
import com.logyard4j.api.event.CapturedAttributeAccess;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MdcImportBoundaryTest {
    @Test
    void stopsBeforeTraversingAnOversizedMapTailEvenWhenEveryKeyIsRejected() {
        LogyardMdcAdapter mdc = new LogyardMdcAdapter();
        String invalid = "x".repeat(CaptureLimits.MAX_ATTRIBUTE_KEY_CHARS + 1);
        int[] visited = {0};
        Map<String, String> endless = new AbstractMap<>() {
            @Override
            public Set<Entry<String, String>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public int size() { throw new AssertionError("size must not be called"); }

                    @Override
                    public Iterator<Entry<String, String>> iterator() {
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() { return true; }

                            @Override
                            public Entry<String, String> next() {
                                if (++visited[0] > LogyardMdcAdapter.MAX_ENTRIES) {
                                    throw new AssertionError("import traversed the discarded tail");
                                }
                                return Map.entry(invalid, "value");
                            }
                        };
                    }
                };
            }
        };
        mdc.setContextMap(endless);
        assertEquals(LogyardMdcAdapter.MAX_ENTRIES, visited[0]);
        assertTrue(mdc.captureLossy());
        assertTrue(CapturedAttributeAccess.truncated(new ContextSnapshotPolicy(List.of("*")).capture(mdc)));
        assertTrue(new ContextSnapshotPolicy(List.of()).capture(mdc).isEmpty());
        mdc.setContextMap(Map.of("request.id", "next"));
        assertFalse(mdc.captureLossy());
        assertEquals("next", mdc.get("request.id"));
    }
}
