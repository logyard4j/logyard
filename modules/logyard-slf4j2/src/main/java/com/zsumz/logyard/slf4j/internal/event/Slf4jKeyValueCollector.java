package com.zsumz.logyard.slf4j.internal.event;

import com.zsumz.logyard.api.event.MessageFormatter;
import com.zsumz.logyard.api.event.AttributeSet;
import org.slf4j.event.KeyValuePair;

import java.util.Iterator;
import java.util.List;

final class Slf4jKeyValueCollector {
    static final int MAX_PAIRS = 256;
    private static final String EVENT_NAME_KEY = "event.name";

    String collect(DeferredAttributes attributes, List<KeyValuePair> pairs, Slf4jCaptureFailures failures) {
        if (pairs == null) {
            return null;
        }
        Iterator<KeyValuePair> iterator;
        try {
            iterator = pairs.iterator();
        } catch (Throwable failure) {
            failures.record(failure);
            return null;
        }

        String eventName = null;
        int visited = 0;
        int invalid = 0;
        boolean truncated = false;
        try {
            while (visited < MAX_PAIRS && iterator.hasNext()) {
                KeyValuePair pair = iterator.next();
                visited++;
                if (pair == null || pair.key == null || pair.key.isBlank() || AttributeSet.isReservedKey(pair.key)) {
                    invalid++;
                } else if (EVENT_NAME_KEY.equals(pair.key)) {
                    if (pair.value != null) {
                        eventName = MessageFormatter.safeToString(pair.value);
                    }
                } else if (!attributes.isFull()) {
                    attributes.put(pair.key, pair.value);
                } else {
                    truncated = true;
                }
            }
            truncated |= iterator.hasNext();
        } catch (Throwable failure) {
            failures.record(failure);
        }
        if (invalid > 0) {
            attributes.putSystem("logyard.slf4j.invalid_key_values", invalid);
        }
        if (truncated) {
            attributes.putSystem("logyard.slf4j.key_values.truncated", true);
        }
        return eventName;
    }
}
