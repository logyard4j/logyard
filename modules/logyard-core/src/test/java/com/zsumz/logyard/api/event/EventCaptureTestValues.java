package com.zsumz.logyard.api.event;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Adversarial value factories and structural measurements shared by event-capture tests. */
final class EventCaptureTestValues {
    private EventCaptureTestValues() {
    }

    static AbstractCollection<Integer> collection(int count, AtomicInteger reads) {
        return new AbstractCollection<>() {
            @Override
            public Iterator<Integer> iterator() {
                return iteratorOf(count, reads);
            }

            @Override
            public int size() {
                throw new AssertionError("generic collection size must not be read");
            }
        };
    }

    static AbstractMap<String, Integer> map(int count, AtomicInteger reads) {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Integer>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Integer>> iterator() {
                        Iterator<Integer> values = iteratorOf(count, reads);
                        return new Iterator<>() {
                            @Override public boolean hasNext() { return values.hasNext(); }
                            @Override public Entry<String, Integer> next() {
                                int value = values.next();
                                return Map.entry("key." + value, value);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        throw new AssertionError("generic map size must not be read");
                    }
                };
            }
        };
    }

    static int capturedDepth(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return 1 + capturedDepth(list.getFirst());
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            return 1 + capturedDepth(map.values().iterator().next());
        }
        return 1;
    }

    static int retainedCharacters(AttributeSet attributes) {
        int total = 0;
        for (int index = 0; index < attributes.size(); index++) {
            total += attributes.keyAt(index).length();
            total += retainedCharacters(attributes.valueAt(index));
        }
        return total;
    }

    private static int retainedCharacters(Object value) {
        if (value instanceof String string) {
            return string.length();
        }
        if (value instanceof List<?> list) {
            return list.stream().mapToInt(EventCaptureTestValues::retainedCharacters).sum();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .mapToInt(entry -> retainedCharacters(entry.getKey()) + retainedCharacters(entry.getValue()))
                    .sum();
        }
        return 0;
    }

    private static Iterator<Integer> iteratorOf(int count, AtomicInteger reads) {
        return new Iterator<>() {
            private int next;

            @Override public boolean hasNext() { return next < count; }
            @Override public Integer next() {
                reads.incrementAndGet();
                return next++;
            }
        };
    }

    enum HostileEnum {
        VALUE;

        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            throw new AssertionError("enum toString must not run");
        }
    }
}
